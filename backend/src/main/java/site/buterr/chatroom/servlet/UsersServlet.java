package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.model.User;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

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
        String username = req.getSession(false) == null
                ? null
                : (String) req.getSession(false).getAttribute("username");
        boolean superAdmin = "Admin".equalsIgnoreCase(username);
        List<User> visible = chatRoom.onlineUsers().stream()
                .filter(user -> superAdmin || !"Admin".equalsIgnoreCase(user.username()))
                .toList();
        JsonUtil.write(resp, HttpServletResponse.SC_OK,
                Map.of("count", visible.size(), "users", visible));
    }
}
