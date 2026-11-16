package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

public class AdminUsersServlet extends HttpServlet {

    private static final int MAX_MUTE_MINUTES = 10_080;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MuteBody(Integer minutes) {
    }

    private final ChatRoom chatRoom;

    public AdminUsersServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || !pathInfo.matches("/[^/]+/mute")) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请求路径不合法"));
            return;
        }
        String username = pathInfo.substring(1, pathInfo.length() - "/mute".length());
        MuteBody body = JsonUtil.read(req, MuteBody.class);
        if (body == null || body.minutes() == null
                || body.minutes() < 0 || body.minutes() > MAX_MUTE_MINUTES) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "禁言时长需为 0~" + MAX_MUTE_MINUTES + " 分钟，0 表示解除禁言"));
            return;
        }
        long mutedUntil = body.minutes() == 0 ? 0 : System.currentTimeMillis() + body.minutes() * 60_000L;
        Optional<Account> account = chatRoom.setMutedUntil(username, mutedUntil);
        if (account.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "账户不存在"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("account", account.get()));
    }
}
