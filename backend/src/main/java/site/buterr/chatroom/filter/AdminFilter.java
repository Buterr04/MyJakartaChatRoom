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

import site.buterr.chatroom.util.JsonUtil;

public class AdminFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpSession session = ((HttpServletRequest) req).getSession(false);
        String role = session == null ? null : (String) session.getAttribute("role");
        if (!"ADMIN".equals(role)) {
            JsonUtil.write((HttpServletResponse) resp, HttpServletResponse.SC_FORBIDDEN,
                    Map.of("error", "需要管理员权限"));
            return;
        }
        chain.doFilter(req, resp);
    }
}
