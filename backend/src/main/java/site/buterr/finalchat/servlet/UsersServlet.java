package site.buterr.finalchat.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.finalchat.store.ChatRoom;
import site.buterr.finalchat.util.JsonUtil;

/**
 * GET /api/users —— 当前在线用户列表。对应 PR03~PR06 首页上的“当前在线用户”。
 */
public class UsersServlet extends HttpServlet {

    private final ChatRoom chatRoom;

    public UsersServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.write(resp, HttpServletResponse.SC_OK,
                Map.of("count", chatRoom.onlineCount(), "users", chatRoom.onlineUsers()));
    }
}
