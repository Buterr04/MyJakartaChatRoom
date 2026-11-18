package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.chatroom.model.User;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

/**
 * GET /api/websocket-status reports why the current session cannot open its
 * chat WebSocket. It is a fallback for browsers that hide close code 1008
 * when the server rejects a connection during its opening handshake.
 */
public class WebSocketStatusServlet extends HttpServlet {

    private final ChatRoom chatRoom;

    public WebSocketStatusServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        User user = session == null ? null : (User) session.getAttribute("chatUser");
        if (user == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "未登录或登录已过期"));
            return;
        }
        chatRoom.webSocketRejection(user, session.getId()).ifPresentOrElse(
                error -> writeConflict(resp, error),
                () -> writeOk(resp));
    }

    private static void writeConflict(HttpServletResponse resp, String error) {
        try {
            JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", error));
        } catch (IOException ignored) {
            // Client disconnected before it could receive the fallback status.
        }
    }

    private static void writeOk(HttpServletResponse resp) {
        try {
            JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("ok", true));
        } catch (IOException ignored) {
            // Client disconnected before it could receive the fallback status.
        }
    }
}
