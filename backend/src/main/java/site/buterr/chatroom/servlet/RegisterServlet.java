package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.store.DuplicateAccountException;
import site.buterr.chatroom.util.JsonUtil;

/** POST /api/register creates a persistent account. */
public class RegisterServlet extends HttpServlet {

    private static final int MIN_USERNAME_LENGTH = 2;
    private static final int MAX_USERNAME_LENGTH = 20;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegisterBody(String username, String displayName, String password) {
    }

    private final ChatRepository repository;

    public RegisterServlet(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        RegisterBody body = JsonUtil.read(req, RegisterBody.class);
        String username = trim(body == null ? null : body.username());
        String displayName = trim(body == null ? null : body.displayName());
        String password = body == null || body.password() == null ? "" : body.password();

        String error = validate(username, displayName, password);
        if (error != null) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", error));
            return;
        }
        try {
            repository.register(username, displayName, password);
            JsonUtil.write(resp, HttpServletResponse.SC_CREATED,
                    Map.of("username", username, "displayName", displayName));
        } catch (DuplicateAccountException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT,
                    Map.of("error", "用户名「" + e.getUsername() + "」已被注册"));
        }
    }

    private static String validate(String username, String displayName, String password) {
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            return "用户名长度需要在 2~20 个字符之间";
        }
        if (displayName.length() < MIN_USERNAME_LENGTH || displayName.length() > MAX_USERNAME_LENGTH) {
            return "显示名称长度需要在 2~20 个字符之间";
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            return "密码长度需要在 8~128 个字符之间";
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
