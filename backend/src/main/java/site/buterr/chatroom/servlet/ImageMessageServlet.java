package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.store.AccountMutedException;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.store.InvalidImageException;
import site.buterr.chatroom.store.UserOfflineException;
import site.buterr.chatroom.util.JsonUtil;

public class ImageMessageServlet extends HttpServlet {

    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final ChatRoom chatRoom;

    public ImageMessageServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Part imagePart = req.getPart("image");
        if (imagePart == null || imagePart.getSize() == 0) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请选择要发送的图片"));
            return;
        }
        if (imagePart.getSize() > MAX_IMAGE_BYTES) {
            JsonUtil.write(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    Map.of("error", "图片不能超过 10MB"));
            return;
        }
        byte[] bytes;
        try (InputStream input = imagePart.getInputStream()) {
            bytes = input.readNBytes((int) MAX_IMAGE_BYTES + 1);
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            JsonUtil.write(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    Map.of("error", "图片不能超过 10MB"));
            return;
        }
        String username = String.valueOf(req.getSession(false).getAttribute("username"));
        try {
            Message message = chatRoom.sendImage(username, imagePart.getSubmittedFileName(), bytes);
            JsonUtil.write(resp, HttpServletResponse.SC_CREATED, Map.of("message", message));
        } catch (InvalidImageException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.getMessage()));
        } catch (AccountMutedException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN,
                    Map.of("error", "账户当前处于禁言状态", "mutedUntil", e.getMutedUntil()));
        } catch (UserOfflineException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", e.getMessage()));
        }
    }
}
