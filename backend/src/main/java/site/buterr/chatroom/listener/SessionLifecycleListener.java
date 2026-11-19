package site.buterr.chatroom.listener;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.store.ChatRoomHolder;

/**
 * 会话生命周期监听器，对应 PR04~PR06 的 SessionLifecycleListener。
 *
 * <p>HTTP 会话销毁（主动登出、超时）时，立即把对应的用户请出聊天室并关闭其 WebSocket。
 * 直接关闭浏览器不会立刻销毁会话，那种情况由 WebSocket 断开后的 30 秒宽限处理。</p>
 *
 * <p>沿用 PR04 web.xml 时代的注册方式：由 Main 按类名注册，因此保留无参构造，
 * ChatRoom 单例从 {@link ChatRoomHolder} 获取。</p>
 */
public class SessionLifecycleListener implements HttpSessionListener {

    private final ChatRoom chatRoom;

    public SessionLifecycleListener() {
        this(ChatRoomHolder.get());
    }

    SessionLifecycleListener(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        String username = (String) se.getSession().getAttribute("username");
        chatRoom.leave(username, se.getSession().getId());
    }
}
