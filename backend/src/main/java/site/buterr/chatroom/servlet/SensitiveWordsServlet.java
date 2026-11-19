package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

/** Admin-only management of the persisted sensitive-word list. */
public class SensitiveWordsServlet extends HttpServlet {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WordBody(String word) {
    }

    private final ChatRoom chatRoom;

    public SensitiveWordsServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("words", chatRoom.listSensitiveWords()));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        WordBody body = JsonUtil.read(req, WordBody.class);
        if (body == null || body.word() == null || body.word().isBlank()) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请输入敏感词"));
            return;
        }
        try {
            if (!chatRoom.addSensitiveWord(body.word())) {
                JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", "敏感词已存在"));
                return;
            }
        } catch (IllegalArgumentException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_CREATED,
                Map.of("words", chatRoom.listSensitiveWords()));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String word = req.getParameter("word");
        try {
            if (word == null || !chatRoom.removeSensitiveWord(word)) {
                JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "敏感词不存在"));
                return;
            }
        } catch (IllegalArgumentException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("words", chatRoom.listSensitiveWords()));
    }
}
