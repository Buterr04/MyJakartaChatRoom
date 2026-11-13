package site.buterr.chatroom.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.Session;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.User;
import site.buterr.chatroom.util.JsonUtil;

/**
 * Runtime chat state: online users, bounded message history and WebSocket connections.
 * Persistent accounts and messages are delegated to an optional repository.
 */
public class ChatRoom {

    public static final int MAX_HISTORY = 200;
    private static final List<String> SENSITIVE_WORDS = List.of("去死", "傻逼", "笨蛋");

    private final int maxHistory;
    private final ChatRepository repository;
    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();
    private final List<Message> history = new ArrayList<>();
    private final Map<String, Set<Session>> wsByHttpSession = new ConcurrentHashMap<>();
    private final Map<Session, Queue<String>> outbound = new ConcurrentHashMap<>();
    private final Set<Session> draining = ConcurrentHashMap.newKeySet();
    private final AtomicLong idGen = new AtomicLong(1);
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chat-broadcaster");
        thread.setDaemon(true);
        return thread;
    });

    /** In-memory constructor retained for focused domain tests. */
    public ChatRoom(int maxHistory) {
        this(maxHistory, null);
    }

    public ChatRoom(int maxHistory, ChatRepository repository) {
        this.maxHistory = maxHistory;
        this.repository = repository;
        if (repository != null) {
            history.addAll(repository.loadRecentMessages(maxHistory));
            history.stream().mapToLong(Message::id).max().ifPresent(last -> idGen.set(last + 1));
        }
    }

    public User login(Account account) {
        return login(account.username(), account.displayName());
    }

    public User login(String username) {
        return login(username, username);
    }

    private User login(String username, String displayName) {
        synchronized (this) {
            for (String name : onlineUsers.keySet()) {
                if (name.equalsIgnoreCase(username)) {
                    throw new DuplicateUsernameException(name);
                }
            }
            User user = new User(username, displayName, System.currentTimeMillis());
            onlineUsers.put(username, user);
            publishSystem(displayName + " 加入了聊天室");
            return user;
        }
    }

    public void leave(String username, String httpSessionId) {
        Set<Session> sockets = wsByHttpSession.remove(httpSessionId);
        if (sockets != null) {
            sockets.forEach(this::closeQuietly);
        }
        if (username == null) {
            return;
        }
        synchronized (this) {
            User user = onlineUsers.remove(username);
            if (user != null) {
                publishSystem(user.displayName() + " 离开了聊天室");
            }
        }
    }

    public Message sendChat(String username, String content) {
        synchronized (this) {
            User sender = onlineUsers.get(username);
            String displayName = sender == null ? username : sender.displayName();
            Message message = createMessage(Message.TYPE_CHAT, username, displayName, filterSensitive(content));
            broadcastPacket(Map.of("type", "message", "data", message));
            return message;
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

    public void bindWs(String httpSessionId, Session ws) {
        wsByHttpSession.computeIfAbsent(httpSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(ws);
        pushOnlineTo(ws);
    }

    public void unbindWs(Session ws) {
        wsByHttpSession.values().forEach(set -> set.remove(ws));
        wsByHttpSession.values().removeIf(Set::isEmpty);
        outbound.remove(ws);
        draining.remove(ws);
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
        long createdAt = System.currentTimeMillis();
        Message message = repository == null
                ? new Message(idGen.getAndIncrement(), type, username, displayName, content, createdAt)
                : repository.saveMessage(type, username, displayName, content, createdAt, maxHistory);
        history.add(message);
        trimHistory();
        return message;
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
            history.remove(0);
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
