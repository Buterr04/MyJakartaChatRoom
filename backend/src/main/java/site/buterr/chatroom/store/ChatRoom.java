package site.buterr.chatroom.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Session;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.ChatImage;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.model.RoomBranding;
import site.buterr.chatroom.model.User;
import site.buterr.chatroom.util.JsonUtil;

/**
 * Runtime chat state: online users, bounded message history and WebSocket connections.
 * Persistent accounts and messages are delegated to an optional repository.
 */
public class ChatRoom {

    public static final int MAX_HISTORY = 200;
    private static final int OUTBOUND_RING_CAPACITY = 128;
    /** A brief network interruption must not make a user leave and rejoin. */
    private static final long OFFLINE_GRACE_PERIOD_MS = 30_000;

    private final int maxHistory;
    private final long offlineGracePeriodMs;
    private final ChatRepository repository;
    private final ImageStorage imageStorage;
    private final LocalConfigStore configStore;
    private volatile RoomBranding roomBranding = RoomBranding.defaults();
    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();
    private final List<Message> history = new ArrayList<>();
    private final Map<String, Set<Session>> wsByHttpSession = new ConcurrentHashMap<>();
    private final Map<Session, User> userByWs = new ConcurrentHashMap<>();
    private final Map<String, String> httpSessionIdByUser = new ConcurrentHashMap<>();
    private final Map<String, String> sessionOwnerByUser = new ConcurrentHashMap<>();
    private final Set<String> revokedSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> pendingOfflineTokens = new ConcurrentHashMap<>();
    private final Set<String> sensitiveWords = ConcurrentHashMap.newKeySet();
    private final AtomicReferenceArray<Frame> outboundRing = new AtomicReferenceArray<>(OUTBOUND_RING_CAPACITY);
    private final AtomicLong publishedSeq = new AtomicLong();
    private final Map<Session, ClientCursor> cursors = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chat-broadcaster");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService presenceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-presence");
        thread.setDaemon(true);
        return thread;
    });

    /** In-memory constructor retained for focused domain tests. */
    public ChatRoom(int maxHistory) {
        this(maxHistory, null, null, null, OFFLINE_GRACE_PERIOD_MS);
    }

    public ChatRoom(int maxHistory, ChatRepository repository) {
        this(maxHistory, repository, null, null, OFFLINE_GRACE_PERIOD_MS);
    }

    public ChatRoom(int maxHistory, ChatRepository repository, ImageStorage imageStorage) {
        this(maxHistory, repository, imageStorage, null, OFFLINE_GRACE_PERIOD_MS);
    }

    public ChatRoom(int maxHistory, ChatRepository repository, ImageStorage imageStorage,
            LocalConfigStore configStore) {
        this(maxHistory, repository, imageStorage, configStore, OFFLINE_GRACE_PERIOD_MS);
    }

    /** Visible for tests that need a repository and a short disconnect grace period. */
    ChatRoom(int maxHistory, ChatRepository repository, long offlineGracePeriodMs) {
        this(maxHistory, repository, null, null, offlineGracePeriodMs);
    }

    /** Visible for tests that verify the disconnect grace period without waiting 30 seconds. */
    ChatRoom(int maxHistory, long offlineGracePeriodMs) {
        this(maxHistory, null, null, null, offlineGracePeriodMs);
    }

    private ChatRoom(int maxHistory, ChatRepository repository, ImageStorage imageStorage,
            LocalConfigStore configStore, long offlineGracePeriodMs) {
        this.maxHistory = maxHistory;
        this.offlineGracePeriodMs = offlineGracePeriodMs;
        this.repository = repository;
        this.imageStorage = imageStorage;
        this.configStore = configStore;
        if (configStore == null) {
            sensitiveWords.addAll(SensitiveWords.DEFAULTS);
        } else {
            sensitiveWords.addAll(configStore.loadSensitiveWords());
            roomBranding = configStore.loadRoomBranding();
        }
        if (repository != null) {
            history.addAll(repository.loadRecentMessages(maxHistory));
            history.stream().mapToLong(Message::id).max().ifPresent(last -> idGen.set(last + 1));
        }
    }

    public User login(Account account) {
        return login(account.username(), account.displayName(), account.role(), account.mutedUntil(), account.avatar());
    }

    /**
     * Claims the account for an HTTP session. A second session is rejected unless
     * {@code force} is true; forcing kicks the previous session's presence and WebSocket.
     * A session that logs in as another account also releases the account it owned before.
     */
    public User login(Account account, String httpSessionId, boolean force) {
        User user = login(account);
        synchronized (this) {
            String owner = findSessionOwner(user.username());
            if (owner != null && !owner.equals(httpSessionId) && !force) {
                throw new AlreadyOnlineException(user.username());
            }
            List<String> previousAccounts = sessionOwnerByUser.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(httpSessionId)
                            && !entry.getKey().equalsIgnoreCase(user.username()))
                    .map(Map.Entry::getKey)
                    .toList();
            for (String previous : previousAccounts) {
                leave(previous, httpSessionId);
            }
            if (owner != null && !owner.equals(httpSessionId)) {
                leave(user.username(), owner, CloseCodes.VIOLATED_POLICY, "该账户已在其他设备登录");
                // The previous browser is kicked for good: after the new owner logs out,
                // its still-alive HTTP session must not silently resume.
                revokedSessions.add(owner);
            }
            sessionOwnerByUser.put(user.username(), httpSessionId);
            // A session that authenticated again is no longer revoked; a session kicked by
            // somebody else's login keeps its revocation.
            revokedSessions.remove(httpSessionId);
        }
        return user;
    }

    public boolean isSessionOwner(String username, String httpSessionId) {
        return httpSessionId != null && httpSessionId.equals(findSessionOwner(username));
    }

    /**
     * True when no session currently owns the account (for example after the offline grace
     * period released it) or when this session is the owner.
     */
    public boolean canUseSession(String username, String httpSessionId) {
        if (httpSessionId == null || revokedSessions.contains(httpSessionId)) {
            return false;
        }
        String owner = findSessionOwner(username);
        return owner == null || owner.equals(httpSessionId);
    }

    public boolean isSessionRevoked(String httpSessionId) {
        return httpSessionId != null && revokedSessions.contains(httpSessionId);
    }

    private String findSessionOwner(String username) {
        return sessionOwnerByUser.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(username))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public User login(String username) {
        return login(username, username, Role.USER, 0, null);
    }

    private User login(String username, String displayName, Role role, long mutedUntil, String avatar) {
        return new User(username, displayName, role, mutedUntil, System.currentTimeMillis(), avatar);
    }

    public void leave(String username, String httpSessionId) {
        leave(username, httpSessionId, CloseCodes.GOING_AWAY, "logged out");
    }

    public void leave(String username, String httpSessionId, CloseCodes closeCode, String reason) {
        List<Session> sockets = List.of();
        synchronized (this) {
            Set<Session> bound = wsByHttpSession.remove(httpSessionId);
            if (bound != null) {
                sockets = List.copyOf(bound);
            }
            List<User> removed = new ArrayList<>();
            for (Session ws : sockets) {
                User user = userByWs.remove(ws);
                if (user != null) {
                    pendingOfflineTokens.remove(user.username());
                    if (onlineUsers.remove(user.username()) != null) {
                        removed.add(user);
                    }
                }
                ClientCursor cursor = cursors.remove(ws);
                if (cursor != null) {
                    cursor.closed = true;
                }
            }
            // The socket may already be gone while the user is still inside the offline grace
            // period: an explicit logout must still remove that pending presence immediately.
            if (username != null && httpSessionId.equals(httpSessionIdByUser.get(username))) {
                User pending = onlineUsers.remove(username);
                if (pending != null && removed.stream()
                        .noneMatch(user -> user.username().equalsIgnoreCase(username))) {
                    removed.add(pending);
                }
                pendingOfflineTokens.remove(username);
                httpSessionIdByUser.remove(username);
            }
            httpSessionIdByUser.entrySet().removeIf(entry -> entry.getValue().equals(httpSessionId));
            if (username != null) {
                sessionOwnerByUser.remove(username, httpSessionId);
            }
            sessionOwnerByUser.entrySet().removeIf(entry -> entry.getValue().equals(httpSessionId));
            revokedSessions.remove(httpSessionId);
            for (User user : removed) {
                publishLeave(user);
            }
        }
        sockets.forEach(ws -> closeQuietly(ws, closeCode, reason));
    }

    private void publishLeave(User user) {
        try {
            publishSystem(user.displayName() + " 离开了聊天室");
        } catch (RuntimeException ignored) {
            // The session is already gone; a failed history write must not abort logout.
        }
    }

    public Message sendChat(String username, String content) {
        synchronized (this) {
            User sender = requireSendAccess(username);
            Message message = createMessage(Message.TYPE_CHAT, username, sender.displayName(),
                    filterSensitive(content));
            broadcastMessage(Map.of("type", "message", "data", message));
            return message;
        }
    }

    public Message sendAssistantMessage(String content) {
        synchronized (this) {
            Message message = createMessage(Message.TYPE_CHAT, null, "AI助手", filterSensitive(content));
            broadcastMessage(Map.of("type", "message", "data", message));
            return message;
        }
    }

    public Message sendImage(String username, String originalName, byte[] bytes) throws IOException {
        synchronized (this) {
            User sender = requireSendAccess(username);
            if (imageStorage == null) {
                throw new IllegalStateException("聊天图片存储尚未配置");
            }
            ChatImage image = imageStorage.store(originalName, bytes);
            try {
                Message message = createMessage(Message.TYPE_CHAT, username, sender.displayName(), "", image);
                broadcastMessage(Map.of("type", "message", "data", message));
                return message;
            } catch (RuntimeException | Error e) {
                try {
                    imageStorage.delete(image);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
        }
    }

    public boolean deleteMessage(long id) {
        synchronized (this) {
            Message target = history.stream()
                    .filter(message -> message.id() == id && Message.TYPE_CHAT.equals(message.type()))
                    .findFirst()
                    .orElse(null);
            if (target == null && repository != null) {
                target = repository.findMessage(id).orElse(null);
            }
            if (target == null || !Message.TYPE_CHAT.equals(target.type())
                    || repository != null && !repository.deleteMessage(id)) {
                return false;
            }
            history.removeIf(message -> message.id() == id);
            deleteStoredImage(target.image());
            broadcastMessage(Map.of("type", "messageDeleted", "data", Map.of("id", id)));
            return true;
        }
    }

    public Optional<Account> setMutedUntil(String username, long mutedUntil) {
        synchronized (this) {
            User online = findOnlineUser(username);
            Account account;
            if (repository != null) {
                Optional<Account> persisted = repository.setMutedUntil(username, mutedUntil);
                if (persisted.isEmpty()) {
                    return Optional.empty();
                }
                account = persisted.get();
                online = findOnlineUser(account.username());
            } else {
                if (online == null) {
                    return Optional.empty();
                }
                account = new Account(online.username(), online.displayName(), online.role(), mutedUntil, online.avatar());
            }
            if (online != null) {
                onlineUsers.put(online.username(), new User(online.username(), online.displayName(),
                        online.role(), mutedUntil, online.loginAt(), online.avatar()));
            }
            broadcastAccount(account);
            return Optional.of(account);
        }
    }

    public Optional<Account> setRole(String username, Role role) {
        synchronized (this) {
            User online = findOnlineUser(username);
            Account account;
            if (repository != null) {
                Optional<Account> persisted = repository.setRole(username, role);
                if (persisted.isEmpty()) {
                    return Optional.empty();
                }
                account = persisted.get();
                online = findOnlineUser(account.username());
            } else {
                if (online == null) {
                    return Optional.empty();
                }
                account = new Account(online.username(), online.displayName(), role, online.mutedUntil(), online.avatar());
            }
            updateOnlineAccount(account);
            broadcastAccount(account);
            return Optional.of(account);
        }
    }

    public Optional<Account> setAvatar(String username, String avatar) {
        synchronized (this) {
            User online = findOnlineUser(username);
            Account account;
            if (repository != null) {
                Optional<Account> persisted = repository.setAvatar(username, avatar);
                if (persisted.isEmpty()) {
                    return Optional.empty();
                }
                account = persisted.get();
            } else {
                if (online == null) {
                    return Optional.empty();
                }
                account = new Account(online.username(), online.displayName(), online.role(), online.mutedUntil(), avatar);
            }
            updateOnlineAccount(account);
            broadcastAccount(account);
            return Optional.of(account);
        }
    }

    private void updateOnlineAccount(Account account) {
        User online = findOnlineUser(account.username());
        if (online != null) {
            onlineUsers.put(online.username(), new User(online.username(), online.displayName(), account.role(),
                    account.mutedUntil(), online.loginAt(), account.avatar()));
        }
    }

    public List<Message> historyAfter(long afterId) {
        synchronized (this) {
            if (afterId <= 0) {
                return List.copyOf(history);
            }
            return history.stream().filter(message -> message.id() > afterId).toList();
        }
    }

    public record MessagePage(List<Message> messages, boolean hasMore) {
    }

    /** Latest messages, oldest first. */
    public MessagePage latestMessages(int limit) {
        if (repository != null) {
            List<Message> rows = repository.loadRecentMessages(limit + 1);
            boolean hasMore = rows.size() > limit;
            List<Message> page = hasMore
                    ? new ArrayList<>(rows.subList(rows.size() - limit, rows.size()))
                    : rows;
            return new MessagePage(page, hasMore);
        }
        synchronized (this) {
            int from = Math.max(0, history.size() - limit);
            boolean hasMore = history.size() > limit;
            return new MessagePage(new ArrayList<>(history.subList(from, history.size())), hasMore);
        }
    }

    /**
     * A page strictly older than {@code beforeId}, oldest first. {@code hasMore} means there
     * are still older rows before this page.
     */
    public MessagePage messagesBefore(long beforeId, int limit) {
        if (repository != null) {
            List<Message> rows = repository.loadMessagesBefore(beforeId, limit + 1);
            boolean hasMore = rows.size() > limit;
            List<Message> page = hasMore
                    ? new ArrayList<>(rows.subList(rows.size() - limit, rows.size()))
                    : rows;
            return new MessagePage(page, hasMore);
        }
        synchronized (this) {
            List<Message> filtered = history.stream().filter(message -> message.id() < beforeId).toList();
            int from = Math.max(0, filtered.size() - limit);
            return new MessagePage(new ArrayList<>(filtered.subList(from, filtered.size())),
                    filtered.size() > limit);
        }
    }

    /** A page strictly newer than {@code afterId}, oldest first. */
    public MessagePage messagesAfter(long afterId, int limit) {
        if (repository != null) {
            List<Message> rows = repository.loadMessagesAfter(afterId, limit + 1);
            boolean hasMore = rows.size() > limit;
            List<Message> page = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
            return new MessagePage(page, hasMore);
        }
        synchronized (this) {
            List<Message> filtered = history.stream().filter(message -> message.id() > afterId).toList();
            return new MessagePage(new ArrayList<>(filtered.subList(0, Math.min(limit, filtered.size()))),
                    filtered.size() > limit);
        }
    }

    public List<User> onlineUsers() {
        return onlineUsers.values().stream().sorted(Comparator.comparingLong(User::loginAt)).toList();
    }

    public int onlineCount() {
        return onlineUsers.size();
    }

    public List<String> listSensitiveWords() {
        return sensitiveWords.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public RoomBranding roomBranding() {
        return roomBranding;
    }

    public synchronized RoomBranding updateRoomBranding(String name, String icon) {
        RoomBranding branding = new RoomBranding(name, icon, System.currentTimeMillis());
        if (configStore != null) {
            configStore.saveRoomBranding(branding);
        }
        roomBranding = branding;
        broadcastRoom();
        return branding;
    }

    public boolean addSensitiveWord(String rawWord) {
        String word = cleanSensitiveWord(rawWord);
        if (sensitiveWords.stream().anyMatch(existing -> existing.equalsIgnoreCase(word))) {
            return false;
        }
        if (configStore != null && !configStore.addSensitiveWord(word)) {
            return false;
        }
        sensitiveWords.add(word);
        return true;
    }

    public boolean removeSensitiveWord(String rawWord) {
        String word = cleanSensitiveWord(rawWord);
        boolean removedFromFile = configStore != null && configStore.removeSensitiveWord(word);
        boolean removedFromMemory = sensitiveWords.removeIf(existing -> existing.equalsIgnoreCase(word));
        return removedFromFile || removedFromMemory;
    }

    private String cleanSensitiveWord(String rawWord) {
        String word = rawWord == null ? "" : rawWord.trim();
        if (word.isEmpty() || word.length() > 50) {
            throw new IllegalArgumentException("敏感词长度需为 1~50 个字符");
        }
        if (word.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("敏感词不能包含控制字符");
        }
        return word;
    }

    /**
     * Checks whether this HTTP session may establish the WebSocket for its account.
     * This is also used as a browser-safe fallback because a close during the WebSocket
     * opening handshake is sometimes reported as code 1006 without a close reason.
     */
    public Optional<String> webSocketRejection(User user, String httpSessionId) {
        synchronized (this) {
            if (revokedSessions.contains(httpSessionId)) {
                return Optional.of("该账户已在其他设备登录");
            }
            String claimed = findSessionOwner(user.username());
            if (claimed != null && !httpSessionId.equals(claimed)) {
                return Optional.of("该账户已在其他连接在线");
            }
            String ownerSessionId = httpSessionIdByUser.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(user.username()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            boolean occupied = onlineUsers.keySet().stream()
                    .anyMatch(username -> username.equalsIgnoreCase(user.username()));
            if (occupied && !httpSessionId.equals(ownerSessionId)) {
                return Optional.of("该账户已在其他连接在线");
            }
            return Optional.empty();
        }
    }

    public void bindWs(User user, String httpSessionId, Session ws) {
        synchronized (this) {
            User effective = refreshUser(user);
            if (webSocketRejection(effective, httpSessionId).isPresent()) {
                throw new DuplicateUsernameException(effective.username());
            }
            if (userByWs.containsKey(ws)) {
                throw new IllegalStateException("WebSocket 已绑定聊天用户");
            }
            // Reconnection within the grace period keeps the existing online entry, but the
            // entry is replaced with a fresh account snapshot so mute/role changes made while
            // the user was offline take effect immediately.
            pendingOfflineTokens.remove(effective.username());
            httpSessionIdByUser.put(effective.username(), httpSessionId);
            sessionOwnerByUser.putIfAbsent(effective.username(), httpSessionId);

            boolean joined = onlineUsers.put(effective.username(), effective) == null;
            if (joined) {
                publishSystem(effective.displayName() + " 加入了聊天室");
            }
            userByWs.put(ws, effective);
            wsByHttpSession.computeIfAbsent(httpSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(ws);
            cursors.put(ws, new ClientCursor(ws, publishedSeq.get() + 1));
        }
        broadcastOnline();
    }

    private User refreshUser(User user) {
        if (repository == null) {
            return user;
        }
        return repository.findAccount(user.username())
                .map(account -> new User(account.username(), account.displayName(), account.role(),
                        account.mutedUntil(), user.loginAt(), account.avatar()))
                .orElse(user);
    }

    public void unbindWs(Session ws) {
        User user = userByWs.remove(ws);
        String httpSessionId = null;
        for (Map.Entry<String, Set<Session>> entry : wsByHttpSession.entrySet()) {
            boolean removed = entry.getValue().remove(ws);
            if (removed) {
                httpSessionId = entry.getKey();
            }
        }
        wsByHttpSession.values().removeIf(Set::isEmpty);
        ClientCursor cursor = cursors.remove(ws);
        if (cursor != null) {
            cursor.closed = true;
        }
        if (user == null || httpSessionId == null) {
            return;
        }
        synchronized (this) {
            if (!onlineUsers.containsKey(user.username())) {
                pendingOfflineTokens.remove(user.username());
                return;
            }
            if (userByWs.values().stream().noneMatch(candidate ->
                    candidate.username().equalsIgnoreCase(user.username()))) {
                scheduleOffline(user.username(), httpSessionId);
            }
        }
    }

    private void scheduleOffline(String username, String httpSessionId) {
        String token = UUID.randomUUID().toString();
        pendingOfflineTokens.put(username, token);
        presenceScheduler.schedule(() -> markOfflineIfStillDisconnected(username, httpSessionId, token),
                offlineGracePeriodMs, TimeUnit.MILLISECONDS);
    }

    private void markOfflineIfStillDisconnected(String username, String httpSessionId, String token) {
        synchronized (this) {
            if (!token.equals(pendingOfflineTokens.get(username))
                    || userByWs.values().stream().anyMatch(user -> user.username().equalsIgnoreCase(username))) {
                return;
            }
            pendingOfflineTokens.remove(username, token);
            User removed = onlineUsers.remove(username);
            httpSessionIdByUser.remove(username, httpSessionId);
            sessionOwnerByUser.remove(username, httpSessionId);
            if (removed != null) {
                publishSystem(removed.displayName() + " 离开了聊天室");
            }
        }
    }

    private void publishSystem(String content) {
        Message message = createMessage(Message.TYPE_SYSTEM, null, null, content);
        broadcastMessage(Map.of("type", "message", "data", message));
        broadcastOnline();
    }

    private Message createMessage(String type, String username, String displayName, String content) {
        return createMessage(type, username, displayName, content, null);
    }

    private Message createMessage(String type, String username, String displayName, String content, ChatImage image) {
        long createdAt = System.currentTimeMillis();
        Message message = repository == null
                ? new Message(idGen.getAndIncrement(), type, username, displayName, content, createdAt, image)
                : repository.saveMessage(type, username, displayName, content, createdAt, image);
        history.add(message);
        trimHistory();
        return message;
    }

    private User requireSendAccess(String username) {
        User sender = onlineUsers.get(username);
        if (sender == null) {
            throw new UserOfflineException("聊天连接尚未建立，请刷新页面");
        }
        if (sender.mutedUntil() > System.currentTimeMillis()) {
            throw new AccountMutedException(sender.mutedUntil());
        }
        return sender;
    }

    private User findOnlineUser(String username) {
        return onlineUsers.values().stream()
                .filter(user -> user.username().equalsIgnoreCase(username))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> onlineSnapshot() {
        return Map.of("count", onlineUsers.size(), "users", onlineUsers());
    }

    private String onlinePacket() {
        return JsonUtil.toJson(Map.of("type", "online", "data", onlineSnapshot()));
    }

    private void broadcastMessage(Object payload) {
        broadcaster.execute(() -> {
            String json = JsonUtil.toJson(payload);
            long seq = publishedSeq.get() + 1;
            outboundRing.set((int) (seq % OUTBOUND_RING_CAPACITY), new Frame(seq, json));
            // Publish the tail only after the slot is visible; cursors never see a half-written frame.
            publishedSeq.set(seq);
            cursors.values().forEach(ClientCursor::signal);
        });
    }

    private void broadcastOnline() {
        broadcaster.execute(() -> {
            String json = onlinePacket();
            cursors.values().forEach(cursor -> {
                cursor.pendingOnline.set(json);
                cursor.signal();
            });
        });
    }

    private void broadcastAccount(Account account) {
        broadcaster.execute(() -> {
            String json = JsonUtil.toJson(Map.of("type", "accountUpdated", "data", account));
            cursors.values().forEach(cursor -> {
                cursor.pendingAccounts.put(account.username(), json);
                cursor.signal();
            });
        });
    }

    private void broadcastRoom() {
        RoomBranding branding = roomBranding;
        broadcaster.execute(() -> {
            String json = JsonUtil.toJson(Map.of("type", "roomUpdated", "data", branding));
            cursors.values().forEach(cursor -> {
                cursor.pendingRoom.set(json);
                cursor.signal();
            });
        });
    }

    public void broadcastAiStatus(boolean enabled) {
        broadcaster.execute(() -> {
            String json = JsonUtil.toJson(Map.of("type", "aiStatus", "data", Map.of("enabled", enabled)));
            cursors.values().forEach(cursor -> {
                cursor.pendingAiStatus.set(json);
                cursor.signal();
            });
        });
    }

    private record Frame(long seq, String json) {
    }

    /**
     * One cursor over the shared ring, plus coalesced state. A cursor only advances after the
     * send callback completes, so a slow client never blocks the broadcaster or other clients.
     */
    private final class ClientCursor {
        private final Session ws;
        private final AtomicLong nextSeq;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private final AtomicReference<String> pendingOnline = new AtomicReference<>();
        private final AtomicReference<String> pendingRoom = new AtomicReference<>();
        private final AtomicReference<String> pendingAiStatus = new AtomicReference<>();
        private final Map<String, String> pendingAccounts = new ConcurrentHashMap<>();
        private volatile boolean closed;

        private ClientCursor(Session ws, long startSeq) {
            this.ws = ws;
            this.nextSeq = new AtomicLong(startSeq);
        }

        private void signal() {
            if (!closed && inFlight.compareAndSet(false, true)) {
                drain();
            }
        }

        private void drain() {
            if (closed) {
                return;
            }
            if (!ws.isOpen()) {
                dispose();
                return;
            }
            String online = pendingOnline.getAndSet(null);
            if (online != null) {
                send(online);
                return;
            }
            String room = pendingRoom.getAndSet(null);
            if (room != null) {
                send(room);
                return;
            }
            String aiStatus = pendingAiStatus.getAndSet(null);
            if (aiStatus != null) {
                send(aiStatus);
                return;
            }
            for (Map.Entry<String, String> entry : pendingAccounts.entrySet()) {
                if (pendingAccounts.remove(entry.getKey(), entry.getValue())) {
                    send(entry.getValue());
                    return;
                }
            }

            long next = nextSeq.get();
            long tail = publishedSeq.get();
            if (next > tail) {
                inFlight.set(false);
                if (hasWork()) {
                    signal();
                }
                return;
            }
            if (tail - next + 1 > OUTBOUND_RING_CAPACITY) {
                overflow();
                return;
            }
            Frame frame = outboundRing.get((int) (next % OUTBOUND_RING_CAPACITY));
            if (frame == null || frame.seq() != next) {
                overflow();
                return;
            }
            nextSeq.incrementAndGet();
            send(frame.json());
        }

        private boolean hasWork() {
            return pendingOnline.get() != null
                    || pendingRoom.get() != null
                    || pendingAiStatus.get() != null
                    || !pendingAccounts.isEmpty()
                    || nextSeq.get() <= publishedSeq.get();
        }

        private void send(String json) {
            try {
                ws.getAsyncRemote().sendText(json, result -> {
                    if (closed) {
                        return;
                    }
                    if (!result.isOK()) {
                        dispose();
                        return;
                    }
                    drain();
                });
            } catch (Exception e) {
                dispose();
            }
        }

        private void overflow() {
            if (closed) {
                return;
            }
            closed = true;
            cursors.remove(ws, this);
            try {
                ws.close(new CloseReason(CloseCodes.TRY_AGAIN_LATER, "发送积压，请重连"));
            } catch (Exception ignored) {
                // Already closed.
            }
        }

        private void dispose() {
            if (closed) {
                return;
            }
            closed = true;
            cursors.remove(ws, this);
            try {
                ws.close(new CloseReason(CloseCodes.GOING_AWAY, "send failed"));
            } catch (Exception ignored) {
                // Container callbacks perform the remaining cleanup.
            }
        }
    }

    private void closeQuietly(Session ws, CloseCodes closeCode, String reason) {
        try {
            ws.close(new CloseReason(closeCode, reason));
        } catch (Exception ignored) {
            // Container callbacks perform the remaining cleanup.
        }
    }

    public void close() {
        broadcaster.shutdownNow();
        presenceScheduler.shutdownNow();
    }

    private void trimHistory() {
        while (history.size() > maxHistory) {
            // The database keeps every message, so only evict the in-memory hot window.
            history.remove(0);
        }
    }

    private void deleteStoredImage(ChatImage image) {
        if (image == null || imageStorage == null) {
            return;
        }
        try {
            imageStorage.delete(image);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("清理聊天图片失败: " + image.id() + " - " + e.getMessage());
        }
    }

    private String filterSensitive(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        NormalizedText haystack = normalizeForFilter(content);
        String normalized = haystack.value();
        int[] originalIndex = haystack.originalIndex();
        boolean[] masked = new boolean[content.length()];
        for (String word : sensitiveWords) {
            NormalizedText needle = normalizeForFilter(word);
            int from = 0;
            while (from < normalized.length()) {
                int index = normalized.indexOf(needle.value(), from);
                if (index < 0) {
                    break;
                }
                int originalStart = originalIndex[index];
                int originalEnd = originalIndex[index + needle.value().length() - 1];
                for (int i = originalStart; i <= originalEnd; i++) {
                    masked[i] = true;
                }
                from = index + needle.value().length();
            }
        }
        StringBuilder result = new StringBuilder(content.length());
        int index = 0;
        while (index < masked.length) {
            if (!masked[index]) {
                result.append(content.charAt(index++));
                continue;
            }
            result.append("**");
            while (index < masked.length && masked[index]) {
                index++;
            }
        }
        return result.toString();
    }

    private record NormalizedText(String value, int[] originalIndex) {
    }

    /** 归一化全角/大小写并去掉常见分隔符，索引映射回原文。 */
    private NormalizedText normalizeForFilter(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        List<Integer> originalIndex = new ArrayList<>(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isFilterSeparator(c)) {
                continue;
            }
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                c = (char) (c - 'Ａ' + 'a');
            } else if (c >= 'ａ' && c <= 'ｚ') {
                c = (char) (c - 'ａ' + 'a');
            }
            normalized.append(c);
            originalIndex.add(i);
        }
        return new NormalizedText(normalized.toString(),
                originalIndex.stream().mapToInt(Integer::intValue).toArray());
    }

    private boolean isFilterSeparator(char c) {
        return Character.isWhitespace(c)
                || c == '*' || c == '＊' || c == '.' || c == '。' || c == '-' || c == '_'
                || c == '·' || c == '~' || c == '～' || c == '!' || c == '！'
                || c == '?' || c == '？' || c == '|' || c == '/' || c == '\\'
                || c == '+' || c == '=' || c == ',' || c == '，' || c == '、'
                || c == ':' || c == '：' || c == ';' || c == '；'
                || c == '(' || c == ')' || c == '（' || c == '）'
                || c == '[' || c == ']' || c == '【' || c == '】'
                || c == '{' || c == '}' || c == '"' || c == '“' || c == '”'
                || c == '\'' || c == '‘' || c == '’';
    }
}
