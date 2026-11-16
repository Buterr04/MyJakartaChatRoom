package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.model.ChatImage;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.store.ImageStorage;
import site.buterr.chatroom.util.JsonUtil;

public class ImageServlet extends HttpServlet {

    private final ChatRepository repository;
    private final ImageStorage storage;

    public ImageServlet(ChatRepository repository, ImageStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || !pathInfo.matches("/[0-9a-fA-F-]{36}")) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "图片不存在"));
            return;
        }
        Optional<ChatImage> image = repository.findImage(pathInfo.substring(1));
        if (image.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "图片不存在"));
            return;
        }
        byte[] bytes;
        try {
            bytes = storage.read(image.get());
        } catch (NoSuchFileException e) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "图片文件不存在"));
            return;
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(image.get().mediaType());
        resp.setContentLength(bytes.length);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "private, max-age=31536000, immutable");
        resp.setHeader("Content-Disposition", "inline; filename=\"image." + extension(image.get().mediaType()) + "\"");
        resp.getOutputStream().write(bytes);
    }

    private static String extension(String mediaType) {
        return "image/jpeg".equals(mediaType) ? "jpg" : mediaType.substring("image/".length());
    }
}
