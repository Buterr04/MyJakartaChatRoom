package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import site.buterr.chatroom.assistant.AiAssistantService;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.store.AccountMutedException;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.store.UserOfflineException;
import site.buterr.chatroom.util.JsonUtil;

/**
 * 消息接口：
 * <ul>
 *   <li>GET /api/messages?limit=50 / before={id} / after={id} —— 历史分页（数据库永久保留）</li>
 *   <li>POST /api/messages —— 发送一条消息（命令走 REST，下行推送走 WebSocket）</li>
 * </ul>
 */
public class MessagesServlet extends HttpServlet {

    public static final int MAX_CONTENT_LENGTH = 500;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendBody(String content) {
    }

    private final ChatRoom chatRoom;
    private final AiAssistantService assistant;

    public MessagesServlet(ChatRoom chatRoom, AiAssistantService assistant) {
        this.chatRoom = chatRoom;
        this.assistant = assistant;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int limit;
        try {
            limit = Integer.parseInt(req.getParameter("limit") == null ? "50" : req.getParameter("limit"));
        } catch (NumberFormatException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "limit 不合法"));
            return;
        }
        if (limit < 1 || limit > 200) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "limit 需要在 1~200 之间"));
            return;
        }

        String afterParam = req.getParameter("after");
        String beforeParam = req.getParameter("before");
        boolean hasAfter = afterParam != null && !afterParam.isBlank();
        boolean hasBefore = beforeParam != null && !beforeParam.isBlank();
        if (hasAfter && hasBefore) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "before 与 after 不能同时使用"));
            return;
        }

        ChatRoom.MessagePage page;
        try {
            if (hasAfter) {
                page = chatRoom.messagesAfter(Long.parseLong(afterParam), limit);
            } else if (hasBefore) {
                page = chatRoom.messagesBefore(Long.parseLong(beforeParam), limit);
            } else {
                page = chatRoom.latestMessages(limit);
            }
        } catch (NumberFormatException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "消息游标不合法"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK,
                Map.of("messages", page.messages(), "hasMore", page.hasMore()));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        SendBody body = JsonUtil.read(req, SendBody.class);
        String content = body == null || body.content() == null ? "" : body.content().trim();

        if (content.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "消息不能为空"));
            return;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "消息不能超过 " + MAX_CONTENT_LENGTH + " 个字符"));
            return;
        }

        HttpSession session = req.getSession(false);
        String username = (String) session.getAttribute("username");
        try {
            Message message = chatRoom.sendChat(username, content);
            assistant.handleUserMessage(message);
            JsonUtil.write(resp, HttpServletResponse.SC_CREATED, Map.of("message", message));
        } catch (AccountMutedException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN,
                    Map.of("error", "账户当前处于禁言状态", "mutedUntil", e.getMutedUntil()));
        } catch (UserOfflineException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", e.getMessage()));
        }
    }
}
