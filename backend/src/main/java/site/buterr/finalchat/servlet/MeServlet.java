package site.buterr.finalchat.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.finalchat.util.JsonUtil;

/**
 * GET /api/me —— 查询当前登录用户。前端刷新页面时用它恢复会话。
 * 能走到这里说明 AuthFilter 已确认登录。
 */
public class MeServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        JsonUtil.write(resp, HttpServletResponse.SC_OK,
                Map.of("username", String.valueOf(session.getAttribute("username"))));
    }
}
