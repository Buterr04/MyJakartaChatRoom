package site.buterr.finalchat.store;

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

import site.buterr.finalchat.model.Message;
import site.buterr.finalchat.model.User;
import site.buterr.finalchat.util.JsonUtil;

/**
 * 聊天室的核心状态：在线用户表、有上限的消息历史、WebSocket 连接登记。
 *
 * <p>对应 PR03~PR06 里散落在 ServletContext 的 users / msgs / onlineCount 三个属性，
 * Final 把它们收拢成一个显式对象。所有写操作统一在对象锁内完成；所有下行推送都
 * 交给单线程执行器，保证每个 WebSocket 连接收到的消息顺序与消息 id 顺序一致。</p>
 */
public class ChatRoom {

    /** 消息历史上限，对应 PR03 的 context-param maxMessageCount */
    public static final int MAX_HISTORY = 200;

    /** 敏感词过滤，沿袭 PR04 的做法 */
    private static final List<String> SENSITIVE_WORDS = List.of("去死", "傻逼", "笨蛋");

    private final int maxHistory;
    private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();
    private final List<Message> history = new ArrayList<>();
    /** httpSession id → 该会话打开的 WebSocket 连接（同一用户可以开多个标签页） */
    private final Map<String, Set<Session>> wsByHttpSession = new ConcurrentHashMap<>();
    /** 每条 WebSocket 连接的出站消息队列，保证同一连接上串行发送 */
    private final Map<Session, Queue<String>> outbound = new ConcurrentHashMap<>();
    /** 正在执行发送链的连接，防止同一连接出现两条并行的发送链 */
    private final Set<Session> draining = ConcurrentHashMap.newKeySet();
    private final AtomicLong idGen = new AtomicLong(1);
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-broadcaster");
        t.setDaemon(true);
        return t;
    });

    public ChatRoom(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    /**
     * 登录：用户名查重（忽略大小写），成功后广播系统消息和最新在线名单。
     *
     * @throws DuplicateUsernameException 用户名已在线
     */
    public User login(String username) {
        synchronized (this) {
            for (String name : onlineUsers.keySet()) {
                if (name.equalsIgnoreCase(username)) {
                    throw new DuplicateUsernameException(name);
                }
            }
            User user = new User(username, System.currentTimeMillis());
            onlineUsers.put(username, user);
            publishSystem(username + " 加入了聊天室");
            return user;
        }
    }

    /**
     * 离场：HTTP 会话销毁（登出/超时/关浏览器）时由 SessionLifecycleListener 调用，
     * 负责关闭该会话的所有 WebSocket 连接，并把用户从在线名单里摘除。
     */
    public void leave(String username, String httpSessionId) {
        Set<Session> sockets = wsByHttpSession.remove(httpSessionId);
        if (sockets != null) {
            sockets.forEach(this::closeQuietly);
        }
        if (username == null) {
            return;
        }
        synchronized (this) {
            if (onlineUsers.remove(username) != null) {
                publishSystem(username + " 离开了聊天室");
            }
        }
    }

    /** 发送一条聊天消息：先过敏感词，再写入历史并广播。 */
    public Message sendChat(String username, String content) {
        synchronized (this) {
            Message message = new Message(idGen.getAndIncrement(), Message.TYPE_CHAT, username,
                    filterSensitive(content), System.currentTimeMillis());
            history.add(message);
            trimHistory();
            broadcastPacket(Map.of("type", "message", "data", message));
            return message;
        }
    }

    /**
     * 查询历史消息，{@code afterId > 0} 时只返回 id 大于它的增量部分。
     */
    public List<Message> historyAfter(long afterId) {
        synchronized (this) {
            if (afterId <= 0) {
                return List.copyOf(history);
            }
            return history.stream().filter(m -> m.id() > afterId).toList();
        }
    }

    public List<User> onlineUsers() {
        return onlineUsers.values().stream()
                .sorted(Comparator.comparingLong(User::loginAt))
                .toList();
    }

    public int onlineCount() {
        return onlineUsers.size();
    }

    // ---------- WebSocket 连接登记 ----------

    /** 新连接登记进房间，并把当前在线名单推给它，让客户端对齐一次状态。 */
    public void bindWs(String httpSessionId, Session ws) {
        wsByHttpSession.computeIfAbsent(httpSessionId, k -> ConcurrentHashMap.newKeySet()).add(ws);
        pushOnlineTo(ws);
    }

    /** 连接关闭时解除登记。 */
    public void unbindWs(Session ws) {
        wsByHttpSession.values().forEach(set -> set.remove(ws));
        wsByHttpSession.values().removeIf(Set::isEmpty);
        outbound.remove(ws);
        draining.remove(ws);
    }

    public void pushOnlineTo(Session ws) {
        broadcaster.execute(() -> enqueue(ws, onlinePacket()));
    }

    // ---------- 内部实现 ----------

    /** 发布系统消息并同步广播在线名单。调用方必须已持有 this 锁。 */
    private void publishSystem(String content) {
        Message message = new Message(idGen.getAndIncrement(), Message.TYPE_SYSTEM, null,
                content, System.currentTimeMillis());
        history.add(message);
        trimHistory();
        broadcastPacket(Map.of("type", "message", "data", message));
        broadcastPacket(Map.of("type", "online", "data", onlineSnapshot()));
    }

    private Map<String, Object> onlineSnapshot() {
        return Map.of("count", onlineUsers.size(), "users", onlineUsers());
    }

    private String onlinePacket() {
        return JsonUtil.toJson(Map.of("type", "online", "data", onlineSnapshot()));
    }

    private void broadcastPacket(Object payload) {
        // 序列化放进执行器里做：单线程执行保证提交顺序即推送顺序
        broadcaster.execute(() -> {
            String json = JsonUtil.toJson(payload);
            wsByHttpSession.values().stream().flatMap(Set::stream)
                    .forEach(ws -> enqueue(ws, json));
        });
    }

    /**
     * 把消息排进指定连接的发送队列并触发刷新。
     *
     * <p>Tomcat 的异步 Remote 不允许同一连接上重叠写入（会抛
     * {@code IllegalStateException: TEXT_FULL_WRITING}），因此每条连接
     * 维护一个出站队列，用 SendHandler 回调链逐条串行发送；
     * 广播线程只负责入队，慢客户端不会拖慢其他连接。</p>
     */
    private void enqueue(Session ws, String json) {
        if (!ws.isOpen()) {
            return;
        }
        outbound.computeIfAbsent(ws, k -> new ConcurrentLinkedQueue<>()).add(json);
        flush(ws);
    }

    private void flush(Session ws) {
        if (!draining.add(ws)) {
            return; // 该连接已有发送链在跑，链尾会取走新消息
        }
        sendNext(ws);
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
                sendNext(ws); // 收尾与入队竞态的兜底
            }
            return;
        }
        try {
            ws.getAsyncRemote().sendText(next, result -> sendNext(ws));
        } catch (Exception e) {
            // 发送链断裂（连接已坏），等待 onClose 统一清理
            outbound.remove(ws);
            draining.remove(ws);
        }
    }

    private void closeQuietly(Session ws) {
        try {
            ws.close(new CloseReason(CloseCodes.GOING_AWAY, "logged out"));
        } catch (Exception ignored) {
            // 关闭失败同样等容器回调清理
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
            if (result.contains(word)) {
                result = result.replace(word, "**");
            }
        }
        return result;
    }
}
