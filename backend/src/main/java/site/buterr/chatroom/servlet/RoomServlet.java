package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

/** Public branding used by the login page and chat header. */
public class RoomServlet extends HttpServlet {

    private final ChatRoom chatRoom;

    public RoomServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("room", chatRoom.roomBranding()));
    }
}
