package site.buterr.finalchat.filter;

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

import site.buterr.finalchat.util.JsonUtil;

/**
 * 登录态过滤器，对应 PR04 的 AuthFilter。
 *
 * <p>区别在于：PR04 服务端渲染页面，未登录时重定向到登录页；
 * Final 是 REST API，未登录时返回 401 + JSON 错误信息，由前端决定如何展示。</p>
 */
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        request.setCharacterEncoding("UTF-8");

        if ("/api/login".equals(request.getServletPath())) {
            chain.doFilter(req, resp);
            return;
        }

        HttpSession session = request.getSession(false);
        String username = session == null ? null : (String) session.getAttribute("username");
        if (username == null) {
            JsonUtil.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Map.of("error", "未登录或登录已过期"));
            return;
        }
        chain.doFilter(req, resp);
    }
}
