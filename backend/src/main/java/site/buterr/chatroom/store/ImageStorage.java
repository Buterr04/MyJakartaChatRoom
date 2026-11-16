package site.buterr.chatroom.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import site.buterr.chatroom.model.ChatImage;

public final class ImageStorage {

    private final Path directory;

    public ImageStorage(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath();
        Files.createDirectories(this.directory);
    }

    public Path directory() {
        return directory;
    }

    public ChatImage store(String originalName, byte[] bytes) throws IOException {
        String mediaType = detectMediaType(bytes);
        if (mediaType == null) {
            throw new InvalidImageException("仅支持 PNG、JPEG、GIF 或 WebP 图片");
        }
        String id = UUID.randomUUID().toString();
        Path target = directory.resolve(id + "." + extension(mediaType));
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return new ChatImage(id, cleanOriginalName(originalName), mediaType, bytes.length);
    }

    public byte[] read(ChatImage image) throws IOException {
        return Files.readAllBytes(path(image));
    }

    public void delete(ChatImage image) throws IOException {
        Files.deleteIfExists(path(image));
    }

    private Path path(ChatImage image) {
        UUID.fromString(image.id());
        return directory.resolve(image.id() + "." + extension(image.mediaType()));
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("不支持的图片类型");
        };
    }

    private static String detectMediaType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if (startsWith(bytes, new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' })) {
            return "image/png";
        }
        if (startsWith(bytes, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF })) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new byte[] { 'G', 'I', 'F', '8', '7', 'a' })
                || startsWith(bytes, new byte[] { 'G', 'I', 'F', '8', '9', 'a' })) {
            return "image/gif";
        }
        if (startsWith(bytes, new byte[] { 'R', 'I', 'F', 'F' })
                && startsWith(bytes, 8, new byte[] { 'W', 'E', 'B', 'P' })) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return startsWith(bytes, 0, prefix);
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String cleanOriginalName(String originalName) {
        String normalized = originalName == null ? "image" : originalName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "_").trim();
        if (normalized.isEmpty()) {
            normalized = "image";
        }
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }
}
