package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.util.JsonUtil;

/** Batch lookup of current avatars for historical message authors. */
public class AvatarLookupServlet extends HttpServlet {

    private static final int MAX_USERNAMES = 100;

    private final ChatRepository repository;

    public AvatarLookupServlet(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String raw = req.getParameter("usernames");
        if (raw == null || raw.isBlank()) {
            JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("avatars", Map.of()));
            return;
        }

        Set<String> usernames = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String username = part.trim();
            if (!username.isEmpty()) {
                usernames.add(username);
            }
        }
        if (usernames.size() > MAX_USERNAMES) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "一次最多查询 " + MAX_USERNAMES + " 个用户头像"));
            return;
        }

        Map<String, String> avatars = new LinkedHashMap<>();
        Map<String, String> found = repository.loadAvatars(new ArrayList<>(usernames));
        for (Map.Entry<String, String> entry : found.entrySet()) {
            avatars.put(entry.getKey(), entry.getValue());
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("avatars", avatars));
    }
}
