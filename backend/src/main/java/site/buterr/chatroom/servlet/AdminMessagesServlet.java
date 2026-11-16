package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

public class AdminMessagesServlet extends HttpServlet {

    private final ChatRoom chatRoom;

    public AdminMessagesServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long id;
        try {
            id = parseMessageId(req.getPathInfo());
        } catch (IllegalArgumentException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "消息 id 不合法"));
            return;
        }
        if (!chatRoom.deleteMessage(id)) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "聊天消息不存在"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("deletedId", id));
    }

    private static long parseMessageId(String pathInfo) {
        if (pathInfo == null || !pathInfo.matches("/[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid message id");
        }
        try {
            return Long.parseLong(pathInfo.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid message id", e);
        }
    }
}
