package site.buterr.finalchat.servlet;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.finalchat.model.Account;
import site.buterr.finalchat.model.User;
import site.buterr.finalchat.store.ChatRepository;
import site.buterr.finalchat.store.ChatRoom;
import site.buterr.finalchat.store.DuplicateUsernameException;
import site.buterr.finalchat.util.JsonUtil;

/** POST /api/login verifies an account password and establishes the HTTP session. */
public class LoginServlet extends HttpServlet {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoginBody(String username, String password) {
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
            User user = chatRoom.login(account.get());
            req.getSession(true).setAttribute("username", user.username());
            req.getSession(false).setAttribute("displayName", user.displayName());
            JsonUtil.write(resp, HttpServletResponse.SC_OK, user);
        } catch (DuplicateUsernameException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT,
                    Map.of("error", "该账户已经在当前聊天室在线"));
        }
    }
}
