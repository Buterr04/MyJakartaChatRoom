package site.buterr.chatroom.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Session;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.ChatImage;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.model.User;
import site.buterr.chatroom.util.JsonUtil;

/**
 * Runtime chat state: online users, bounded message history and WebSocket connections.
 * Persistent accounts and messages are delegated to an optional repository.
 */
public class ChatRoom {

    public static final int MAX_HISTORY = 200;
    private static final List<String> SENSITIVE_WORDS = List.of("去死", "傻逼", "笨蛋");
    /** A brief network interruption must not make a user leave and rejoin. */
    private static final long OFFLINE_GRACE_PERIOD_MS = 30_000;

    private final int maxHistory;
    private final long offlineGracePeriodMs;
    private final ChatRepository repository;
    private final ImageStorage imageStorage;
    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();
    private final List<Message> history = new ArrayList<>();
    private final Map<String, Set<Session>> wsByHttpSession = new ConcurrentHashMap<>();
    private final Map<Session, User> userByWs = new ConcurrentHashMap<>();
    private final Map<String, String> httpSessionIdByUser = new ConcurrentHashMap<>();
    private final Map<String, String> pendingOfflineTokens = new ConcurrentHashMap<>();
    private final Map<Session, Queue<String>> outbound = new ConcurrentHashMap<>();
    private final Set<Session> draining = ConcurrentHashMap.newKeySet();
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
        this(maxHistory, null, null, OFFLINE_GRACE_PERIOD_MS);
    }

    public ChatRoom(int maxHistory, ChatRepository repository) {
        this(maxHistory, repository, null, OFFLINE_GRACE_PERIOD_MS);
    }

    public ChatRoom(int maxHistory, ChatRepository repository, ImageStorage imageStorage) {
        this(maxHistory, repository, imageStorage, OFFLINE_GRACE_PERIOD_MS);
    }

    /** Visible for tests that verify the disconnect grace period without waiting 30 seconds. */
    ChatRoom(int maxHistory, long offlineGracePeriodMs) {
        this(maxHistory, null, null, offlineGracePeriodMs);
    }

    private ChatRoom(int maxHistory, ChatRepository repository, ImageStorage imageStorage, long offlineGracePeriodMs) {
        this.maxHistory = maxHistory;
        this.offlineGracePeriodMs = offlineGracePeriodMs;
        this.repository = repository;
        this.imageStorage = imageStorage;
        if (repository != null) {
            history.addAll(repository.loadRecentMessages(maxHistory));
            history.stream().mapToLong(Message::id).max().ifPresent(last -> idGen.set(last + 1));
        }
    }

    public User login(Account account) {
        return login(account.username(), account.displayName(), account.role(), account.mutedUntil(), account.avatar());
    }

    public User login(String username) {
        return login(username, username, Role.USER, 0, null);
    }

    private User login(String username, String displayName, Role role, long mutedUntil, String avatar) {
        return new User(username, displayName, role, mutedUntil, System.currentTimeMillis(), avatar);
    }

    public void leave(String username, String httpSessionId) {
        Set<Session> sockets = wsByHttpSession.get(httpSessionId);
        if (sockets != null) {
            List.copyOf(sockets).forEach(this::closeQuietly);
        }
    }

    public Message sendChat(String username, String content) {
        synchronized (this) {
            User sender = requireSendAccess(username);
            Message message = createMessage(Message.TYPE_CHAT, username, sender.displayName(),
                    filterSensitive(content));
            broadcastPacket(Map.of("type", "message", "data", message));
            return message;
        }
    }

    public Message sendAssistantMessage(String content) {
        synchronized (this) {
            Message message = createMessage(Message.TYPE_CHAT, null, "AI助手", filterSensitive(content));
            broadcastPacket(Map.of("type", "message", "data", message));
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
                broadcastPacket(Map.of("type", "message", "data", message));
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
            if (target == null || repository != null && !repository.deleteMessage(id)) {
                return false;
            }
            history.remove(target);
            deleteStoredImage(target.image());
            broadcastPacket(Map.of("type", "messageDeleted", "data", Map.of("id", id)));
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
            broadcastPacket(Map.of("type", "accountUpdated", "data", account));
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
            broadcastPacket(Map.of("type", "accountUpdated", "data", account));
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
            broadcastPacket(Map.of("type", "accountUpdated", "data", account));
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

    public List<User> onlineUsers() {
        return onlineUsers.values().stream().sorted(Comparator.comparingLong(User::loginAt)).toList();
    }

    public int onlineCount() {
        return onlineUsers.size();
    }

    /**
     * Checks whether this HTTP session may establish the WebSocket for its account.
     * This is also used as a browser-safe fallback because a close during the WebSocket
     * opening handshake is sometimes reported as code 1006 without a close reason.
     */
    public Optional<String> webSocketRejection(User user, String httpSessionId) {
        synchronized (this) {
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
            if (webSocketRejection(user, httpSessionId).isPresent()) {
                throw new DuplicateUsernameException(user.username());
            }
            // Reconnection within the grace period keeps the existing online entry.
            pendingOfflineTokens.remove(user.username());
            httpSessionIdByUser.put(user.username(), httpSessionId);

            User online = onlineUsers.getOrDefault(user.username(), user);
            boolean joined = onlineUsers.putIfAbsent(user.username(), online) == null;
            if (joined) {
                publishSystem(online.displayName() + " 加入了聊天室");
            }
            if (userByWs.containsKey(ws)) {
                throw new IllegalStateException("WebSocket 已绑定聊天用户");
            }
            userByWs.put(ws, online);
            wsByHttpSession.computeIfAbsent(httpSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(ws);
        }
        broadcastPacket(Map.of("type", "online", "data", onlineSnapshot()));
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
        outbound.remove(ws);
        draining.remove(ws);
        if (user == null || httpSessionId == null) {
            return;
        }
        synchronized (this) {
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
            if (removed != null) {
                publishSystem(removed.displayName() + " 离开了聊天室");
            }
        }
    }

    public void pushOnlineTo(Session ws) {
        broadcaster.execute(() -> enqueue(ws, onlinePacket()));
    }

    private void publishSystem(String content) {
        Message message = createMessage(Message.TYPE_SYSTEM, null, null, content);
        broadcastPacket(Map.of("type", "message", "data", message));
        broadcastPacket(Map.of("type", "online", "data", onlineSnapshot()));
    }

    private Message createMessage(String type, String username, String displayName, String content) {
        return createMessage(type, username, displayName, content, null);
    }

    private Message createMessage(String type, String username, String displayName, String content, ChatImage image) {
        long createdAt = System.currentTimeMillis();
        Message message = repository == null
                ? new Message(idGen.getAndIncrement(), type, username, displayName, content, createdAt, image)
                : repository.saveMessage(type, username, displayName, content, createdAt, maxHistory, image);
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

    private void broadcastPacket(Object payload) {
        broadcaster.execute(() -> {
            String json = JsonUtil.toJson(payload);
            wsByHttpSession.values().stream().flatMap(Set::stream).forEach(ws -> enqueue(ws, json));
        });
    }

    private void enqueue(Session ws, String json) {
        if (!ws.isOpen()) {
            return;
        }
        outbound.computeIfAbsent(ws, ignored -> new ConcurrentLinkedQueue<>()).add(json);
        flush(ws);
    }

    private void flush(Session ws) {
        if (draining.add(ws)) {
            sendNext(ws);
        }
    }

    private void sendNext(Session ws) {
        if (!ws.isOpen()) {
            outbound.remove(ws);
            draining.remove(ws);
            return;
        }
        Queue<String> queue = outbound.get(ws);
        String next = queue == null ? null : queue.poll();
        if (next == null) {
            draining.remove(ws);
            if (queue != null && !queue.isEmpty() && draining.add(ws)) {
                sendNext(ws);
            }
            return;
        }
        try {
            ws.getAsyncRemote().sendText(next, result -> sendNext(ws));
        } catch (Exception ignored) {
            outbound.remove(ws);
            draining.remove(ws);
        }
    }

    private void closeQuietly(Session ws) {
        try {
            ws.close(new CloseReason(CloseCodes.GOING_AWAY, "logged out"));
        } catch (Exception ignored) {
            // Container callbacks perform the remaining cleanup.
        }
    }

    private void trimHistory() {
        while (history.size() > maxHistory) {
            Message removed = history.remove(0);
            deleteStoredImage(removed.image());
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
        String result = content;
        for (String word : SENSITIVE_WORDS) {
            result = result.replace(word, "**");
        }
        return result;
    }
}
