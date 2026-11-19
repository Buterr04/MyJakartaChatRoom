package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.User;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.util.JsonUtil;

/**
 * GET /api/me —— 查询当前登录用户。前端刷新页面时用它恢复会话。
 * 能走到这里说明 AuthFilter 已确认登录。
 */
public class MeServlet extends HttpServlet {

    private final ChatRepository repository;

    public MeServlet(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Optional<Account> account = repository.findAccount(String.valueOf(session.getAttribute("username")));
        if (account.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "账户不存在"));
            return;
        }
        session.setAttribute("role", account.get().role().name());
        session.setAttribute("mutedUntil", account.get().mutedUntil());
        Object current = session.getAttribute("chatUser");
        long loginAt = current instanceof User user ? user.loginAt() : System.currentTimeMillis();
        session.setAttribute("chatUser", new User(account.get().username(), account.get().displayName(),
                account.get().role(), account.get().mutedUntil(), loginAt, account.get().avatar()));
        JsonUtil.write(resp, HttpServletResponse.SC_OK, account.get());
    }
}
