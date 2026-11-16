package site.buterr.chatroom.filter;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.util.JsonUtil;

public class AdminFilter implements Filter {

    private final ChatRepository repository;

    public AdminFilter(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpSession session = ((HttpServletRequest) req).getSession(false);
        String username = session == null ? null : (String) session.getAttribute("username");
        Account account = username == null ? null : repository.findAccount(username).orElse(null);
        if (account == null || account.role() != Role.ADMIN) {
            JsonUtil.write((HttpServletResponse) resp, HttpServletResponse.SC_FORBIDDEN,
                    Map.of("error", "需要管理员权限"));
            return;
        }
        session.setAttribute("role", account.role().name());
        chain.doFilter(req, resp);
    }
}
