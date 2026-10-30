package site.buterr.finalchat.store;

/**
 * 把 {@link ChatRoom} 单例交给 WebSocket 端点。
 *
 * <p>jakarta.websocket 的端点实例拿不到 ServletContext，而内嵌模式下
 * Main 启动时会先创建 ChatRoom 再注入各处，这里用一个静态引用兜底给端点；
 * 应用是单实例部署，这样做足够安全。</p>
 */
public final class ChatRoomHolder {

    private static volatile ChatRoom room;

    private ChatRoomHolder() {
    }

    public static void set(ChatRoom chatRoom) {
        room = chatRoom;
    }

    public static ChatRoom get() {
        ChatRoom current = room;
        if (current == null) {
            throw new IllegalStateException("ChatRoom 尚未初始化");
        }
        return current;
    }
}
