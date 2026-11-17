package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.util.JsonUtil;

/** PUT /api/me/password verifies the current password before replacing it. */
public class PasswordServlet extends HttpServlet {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PasswordBody(String currentPassword, String newPassword) {
    }

    private final ChatRepository repository;

    public PasswordServlet(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PasswordBody body = JsonUtil.read(req, PasswordBody.class);
        String currentPassword = body == null || body.currentPassword() == null ? "" : body.currentPassword();
        String newPassword = body == null || body.newPassword() == null ? "" : body.newPassword();
        if (currentPassword.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请输入当前密码"));
            return;
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH || newPassword.length() > MAX_PASSWORD_LENGTH) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "新密码长度需要在 8~128 个字符之间"));
            return;
        }
        String username = (String) req.getSession(false).getAttribute("username");
        if (!repository.changePassword(username, currentPassword, newPassword)) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "当前密码不正确"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("ok", true));
    }
}
