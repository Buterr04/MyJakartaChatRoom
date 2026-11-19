package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.User;
import site.buterr.chatroom.store.AlreadyOnlineException;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

/** POST /api/login verifies an account password and establishes the HTTP session. */
public class LoginServlet extends HttpServlet {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoginBody(String username, String password, Boolean force) {
    }

    private final ChatRoom chatRoom;
    private final ChatRepository repository;

    public LoginServlet(ChatRoom chatRoom, ChatRepository repository) {
        this.chatRoom = chatRoom;
        this.repository = repository;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        LoginBody body = JsonUtil.read(req, LoginBody.class);
        String username = body == null || body.username() == null ? "" : body.username().trim();
        String password = body == null || body.password() == null ? "" : body.password();
        if (username.isEmpty() || password.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请输入用户名和密码"));
            return;
        }

        Optional<Account> account = repository.authenticate(username, password);
        if (account.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "用户名或密码错误"));
            return;
        }
        try {
            HttpSession session = req.getSession(true);
            boolean force = body != null && Boolean.TRUE.equals(body.force());
            User user = chatRoom.login(account.get(), session.getId(), force);
            repository.markLogin(user.username());
            session.setAttribute("chatUser", user);
            session.setAttribute("username", user.username());
            session.setAttribute("displayName", user.displayName());
            session.setAttribute("role", user.role().name());
            session.setAttribute("mutedUntil", user.mutedUntil());
            JsonUtil.write(resp, HttpServletResponse.SC_OK, user);
        } catch (AlreadyOnlineException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT,
                    Map.of("error", "该账户已在其他设备登录", "code", "already_online"));
        }
    }
}
