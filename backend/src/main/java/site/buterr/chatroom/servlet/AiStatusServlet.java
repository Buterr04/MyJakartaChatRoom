package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.assistant.AiAssistantService;
import site.buterr.chatroom.util.JsonUtil;

/** Whether the composer should expose the @AI shortcut. */
public class AiStatusServlet extends HttpServlet {

    private final AiAssistantService assistant;

    public AiStatusServlet(AiAssistantService assistant) {
        this.assistant = assistant;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("enabled", assistant.isEnabled()));
    }
}
