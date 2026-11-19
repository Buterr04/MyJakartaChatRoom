package site.buterr.chatroom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 聊天室启动入口：内嵌 Tomcat 10.1，{@code mvn compile exec:java} 一条命令即可运行，
 * 编程模型仍是纯 jakarta.servlet / jakarta.websocket。
 *
 * <p>可选环境变量：{@code APP_PORT}（默认 8080）、
 * {@code APP_STATIC_DIR}（前端构建产物目录，默认自动探测 ../frontend/dist）、
 * {@code APP_DB_PATH}（SQLite 数据库，默认 chatroom.db）、
 * {@code APP_UPLOAD_DIR}（聊天图片目录，默认 uploads）、
 * {@code APP_CONFIG_DIR}（敏感词/AI/房间配置目录，默认 config）。</p>
 */
public final class Main {

    private static final int DEFAULT_PORT = 8080;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", String.valueOf(DEFAULT_PORT)));
        Path staticDir = resolveStaticDir();
        ChatServer server = ChatServer.start(new ChatServer.Config(
                port,
                resolveDatabasePath(),
                resolveUploadDirectory(),
                resolveConfigDirectory(),
                staticDir));
        System.out.println("聊天室已启动: " + server.baseUrl()
                + "  (静态资源目录: " + staticDir + ")");
        server.await();
    }

    private static Path resolveDatabasePath() {
        String configured = System.getenv("APP_DB_PATH");
        return configured == null || configured.isBlank() ? Path.of("chatroom.db") : Path.of(configured);
    }

    private static Path resolveUploadDirectory() {
        String configured = System.getenv("APP_UPLOAD_DIR");
        return configured == null || configured.isBlank() ? Path.of("uploads") : Path.of(configured);
    }

    private static Path resolveConfigDirectory() {
        String configured = System.getenv("APP_CONFIG_DIR");
        return configured == null || configured.isBlank() ? Path.of("config") : Path.of(configured);
    }

    /**
     * 前端构建产物目录的探测顺序：环境变量 → ../frontend/dist（从 backend 目录启动）→
     * frontend/dist（从仓库根目录启动）→ 临时空目录（纯 API 模式）。
     */
    private static Path resolveStaticDir() throws IOException {
        String configured = System.getenv("APP_STATIC_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path fromBackend = Path.of("../frontend/dist");
        if (Files.isDirectory(fromBackend)) {
            return fromBackend;
        }
        Path fromRoot = Path.of("frontend/dist");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        return Files.createTempDirectory("chatroom-empty");
    }
}
