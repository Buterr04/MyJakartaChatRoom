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
import site.buterr.chatroom.util.JsonUtil;

/**
 * 消息接口：
 * <ul>
 *   <li>GET /api/messages?after={id} —— 拉取历史消息，after 为增量拉取游标</li>
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
        long after;
        try {
            after = Long.parseLong(req.getParameter("after"));
        } catch (NumberFormatException e) {
            after = 0;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("messages", chatRoom.historyAfter(after)));
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
        }
    }
}
