package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

public class AvatarServlet extends HttpServlet {

    private static final int MAX_AVATAR_BYTES = 1024 * 1024;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvatarBody(String avatar) {
    }

    private final ChatRoom chatRoom;

    public AvatarServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        AvatarBody body = JsonUtil.read(req, AvatarBody.class);
        if (body == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请求体不合法"));
            return;
        }
        String avatar = body.avatar() == null ? "" : body.avatar().trim();
        if (!avatar.isEmpty() && !isValidAvatar(avatar)) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "头像仅支持 1MB 以内的 PNG、JPEG、GIF 或 WebP 图片"));
            return;
        }
        String username = String.valueOf(req.getSession(false).getAttribute("username"));
        Optional<Account> account = chatRoom.setAvatar(username, avatar.isEmpty() ? null : avatar);
        if (account.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "账户不存在"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, account.get());
    }

    private static boolean isValidAvatar(String avatar) {
        int separator = avatar.indexOf(";base64,");
        if (separator < 0) {
            return false;
        }
        String mimeType = avatar.substring(0, separator);
        if (!mimeType.matches("data:image/(png|jpeg|gif|webp)")) {
            return false;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(avatar.substring(separator + 8));
            return bytes.length > 0 && bytes.length <= MAX_AVATAR_BYTES && matchesSignature(mimeType, bytes);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean matchesSignature(String mimeType, byte[] bytes) {
        if (bytes.length < 12) {
            return false;
        }
        return switch (mimeType) {
            case "data:image/png" -> startsWith(bytes, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
            case "data:image/jpeg" -> startsWith(bytes, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF });
            case "data:image/gif" -> startsWith(bytes, new byte[] { 'G', 'I', 'F', '8' });
            case "data:image/webp" -> startsWith(bytes, new byte[] { 'R', 'I', 'F', 'F' })
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
