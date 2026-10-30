package site.buterr.finalchat.chat;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import site.buterr.finalchat.store.ChatRoom;
import site.buterr.finalchat.store.ChatRoomHolder;

/**
 * 聊天室 WebSocket 端点：/ws/chat。
 *
 * <p>Final 的实时通道只做“服务端 → 客户端”的下行推送（新消息、在线名单变化），
 * 发消息等命令一律走 REST（PR01 以来的 request/response 语义，能拿到明确的
 * 400/409 状态码）。客户端发送 "ping" 可以得到 "pong"，用于保活探测。</p>
 *
 * <p>未登录的握手会被拒绝：{@link ChatConfigurator} 传进来的 HTTP 会话里没有
 * username 时，端点立即以 1008（VIOLATED_POLICY）关闭，前端据此回到登录页。</p>
 */
@ServerEndpoint(value = "/ws/chat", configurator = ChatConfigurator.class)
public class ChatEndpoint {

    private final HttpSession httpSession;

    public ChatEndpoint(HttpSession httpSession) {
        this.httpSession = httpSession;
    }

    @OnOpen
    public void onOpen(Session session) throws Exception {
        String username = httpSession == null ? null : (String) httpSession.getAttribute("username");
        if (username == null) {
            session.close(new CloseReason(CloseCodes.VIOLATED_POLICY, "未登录"));
            return;
        }
        ChatRoomHolder.get().bindWs(httpSession.getId(), session);
    }

    @OnMessage
    public void onMessage(Session session, String text) {
        if ("ping".equals(text)) {
            session.getAsyncRemote().sendText("pong");
        }
    }

    @OnClose
    public void onClose(Session session) {
        ChatRoomHolder.get().unbindWs(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        ChatRoomHolder.get().unbindWs(session);
    }
}
