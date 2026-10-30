package site.buterr.finalchat.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.finalchat.util.JsonUtil;

/**
 * POST /api/logout —— 登出。销毁 HttpSession 后，SessionLifecycleListener
 * 会负责把用户请出聊天室、关闭其 WebSocket 连接并广播离场消息。
 */
public class LogoutServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("ok", true));
    }
}
