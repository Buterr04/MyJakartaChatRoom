package site.buterr.finalchat.listener;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import site.buterr.finalchat.store.ChatRoom;
import site.buterr.finalchat.store.ChatRoomHolder;

/**
 * 会话生命周期监听器，对应 PR04~PR06 的 SessionLifecycleListener。
 *
 * <p>HTTP 会话销毁（主动登出、超时、直接关浏览器）时，把对应的用户请出聊天室。
 * 在线人数不再由监听器手工加减，而是直接由 ChatRoom 的在线用户表派生，
 * 永远不会出现计数对不上的问题。</p>
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
