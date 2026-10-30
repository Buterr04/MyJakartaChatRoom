package site.buterr.finalchat.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.finalchat.model.User;
import site.buterr.finalchat.store.ChatRoom;
import site.buterr.finalchat.store.DuplicateUsernameException;
import site.buterr.finalchat.util.JsonUtil;

/**
 * POST /api/login —— 登录。
 *
 * <p>沿用 PR03 的 PRG 思路改为前后端分离后的形态：成功即建立 HttpSession，
 * 失败返回 400/409 + JSON 错误信息，由前端渲染。</p>
 */
public class LoginServlet extends HttpServlet {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoginBody(String username) {
    }

    private final ChatRoom chatRoom;

    public LoginServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        LoginBody body = JsonUtil.read(req, LoginBody.class);
        String username = body == null || body.username() == null ? "" : body.username().trim();

        if (username.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "用户名不能为空"));
            return;
        }
        if (username.length() < 2 || username.length() > 20) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "用户名长度需要在 2~20 个字符之间"));
            return;
        }

        try {
            User user = chatRoom.login(username);
            req.getSession(true).setAttribute("username", user.username());
            JsonUtil.write(resp, HttpServletResponse.SC_OK, user);
        } catch (DuplicateUsernameException e) {
            JsonUtil.write(resp, 409, Map.of("error", "用户名「" + e.getUsername() + "」已经在线了，换一个试试"));
        }
    }
}
