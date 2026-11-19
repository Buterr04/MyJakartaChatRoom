package site.buterr.chatroom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

import jakarta.servlet.MultipartConfigElement;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;

import site.buterr.chatroom.assistant.AiAssistantService;
import site.buterr.chatroom.chat.ChatEndpoint;
import site.buterr.chatroom.filter.AdminFilter;
import site.buterr.chatroom.filter.AuthFilter;
import site.buterr.chatroom.filter.SuperAdminFilter;
import site.buterr.chatroom.listener.SessionLifecycleListener;
import site.buterr.chatroom.servlet.AdminMessagesServlet;
import site.buterr.chatroom.servlet.AdminUsersServlet;
import site.buterr.chatroom.servlet.AiSettingsServlet;
import site.buterr.chatroom.servlet.AiStatusServlet;
import site.buterr.chatroom.servlet.AvatarServlet;
import site.buterr.chatroom.servlet.ImageMessageServlet;
import site.buterr.chatroom.servlet.ImageServlet;
import site.buterr.chatroom.servlet.LoginServlet;
import site.buterr.chatroom.servlet.LogoutServlet;
import site.buterr.chatroom.servlet.MeServlet;
import site.buterr.chatroom.servlet.MessagesServlet;
import site.buterr.chatroom.servlet.PasswordServlet;
import site.buterr.chatroom.servlet.RegisterServlet;
import site.buterr.chatroom.servlet.RoomServlet;
import site.buterr.chatroom.servlet.RoomSettingsServlet;
import site.buterr.chatroom.servlet.SensitiveWordsServlet;
import site.buterr.chatroom.servlet.StatisticsServlet;
import site.buterr.chatroom.servlet.UsersServlet;
import site.buterr.chatroom.servlet.WebSocketStatusServlet;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.store.ChatRoomHolder;
import site.buterr.chatroom.store.ImageStorage;
import site.buterr.chatroom.store.LocalConfigStore;

/**
 * Starts an embedded Tomcat with an isolated runtime configuration.
 *
 * <p>Production uses {@link Config} built from environment variables in {@link Main};
 * integration tests use a random port and temporary database/config/upload/static directories.</p>
 */
public final class ChatServer implements AutoCloseable {

    public record Config(int port, Path database, Path uploads, Path config, Path staticDirectory) {
    }

    private final Tomcat tomcat;
    private final Path baseDirectory;
    private final AiAssistantService assistant;
    private final ChatRepository repository;
    private final ChatRoom chatRoom;
    private int port;

    private ChatServer(Tomcat tomcat, Path baseDirectory, AiAssistantService assistant,
            ChatRepository repository, ChatRoom chatRoom) {
        this.tomcat = tomcat;
        this.baseDirectory = baseDirectory;
        this.assistant = assistant;
        this.repository = repository;
        this.chatRoom = chatRoom;
    }

    public static ChatServer start(Config config) throws Exception {
        Path database = config.database().toAbsolutePath();
        Path uploads = config.uploads().toAbsolutePath();
        Path configDirectory = config.config().toAbsolutePath();
        Path staticDirectory = config.staticDirectory().toAbsolutePath();
        if (database.getParent() != null) {
            Files.createDirectories(database.getParent());
        }
        Files.createDirectories(uploads);
        Files.createDirectories(configDirectory);
        Files.createDirectories(staticDirectory);

        ChatRepository repository = new ChatRepository(database);
        LocalConfigStore configStore = new LocalConfigStore(configDirectory);
        configStore.migrateFrom(repository);
        repository.dropLegacyConfigTables();
        ImageStorage imageStorage = new ImageStorage(uploads);
        ChatRoom chatRoom = new ChatRoom(ChatRoom.MAX_HISTORY, repository, imageStorage, configStore);
        AiAssistantService assistant = new AiAssistantService(configStore, chatRoom);
        ChatRoomHolder.set(chatRoom);

        Tomcat tomcat = new Tomcat();
        Path baseDirectory = Files.createTempDirectory("chatroom-tomcat-");
        tomcat.setBaseDir(baseDirectory.toString());
        tomcat.setPort(config.port());
        tomcat.getConnector();

        Context ctx = tomcat.addContext("", staticDirectory.toString());
        ctx.setSessionTimeout(60);
        StandardContext standardCtx = (StandardContext) ctx;
        standardCtx.setParentClassLoader(ChatServer.class.getClassLoader());
        standardCtx.addApplicationListener(SessionLifecycleListener.class.getName());

        Wrapper defaultServlet = Tomcat.addServlet(standardCtx, "default", new DefaultServlet());
        defaultServlet.addMapping("/");
        standardCtx.addWelcomeFile("index.html");
        standardCtx.addMimeMapping("js", "application/javascript");
        standardCtx.addMimeMapping("css", "text/css");
        standardCtx.addMimeMapping("html", "text/html");
        standardCtx.addMimeMapping("htm", "text/html");
        standardCtx.addMimeMapping("json", "application/json");
        standardCtx.addMimeMapping("map", "application/json");
        standardCtx.addMimeMapping("svg", "image/svg+xml");
        standardCtx.addMimeMapping("png", "image/png");
        standardCtx.addMimeMapping("ico", "image/x-icon");
        standardCtx.addMimeMapping("woff2", "font/woff2");

        ctx.addServletContainerInitializer(new WsSci(), Set.<Class<?>>of(ChatEndpoint.class));

        FilterDef authDef = new FilterDef();
        authDef.setFilterName("authFilter");
        authDef.setFilter(new AuthFilter(chatRoom));
        ctx.addFilterDef(authDef);
        FilterMap authMap = new FilterMap();
        authMap.setFilterName("authFilter");
        authMap.addURLPattern("/api/*");
        ctx.addFilterMap(authMap);

        FilterDef adminDef = new FilterDef();
        adminDef.setFilterName("adminFilter");
        adminDef.setFilter(new AdminFilter(repository));
        ctx.addFilterDef(adminDef);
        FilterMap adminMap = new FilterMap();
        adminMap.setFilterName("adminFilter");
        adminMap.addURLPattern("/api/admin/*");
        ctx.addFilterMap(adminMap);

        FilterDef superAdminDef = new FilterDef();
        superAdminDef.setFilterName("superAdminFilter");
        superAdminDef.setFilter(new SuperAdminFilter(repository));
        ctx.addFilterDef(superAdminDef);
        FilterMap superAdminMap = new FilterMap();
        superAdminMap.setFilterName("superAdminFilter");
        superAdminMap.addURLPattern("/api/super-admin/*");
        ctx.addFilterMap(superAdminMap);

        Tomcat.addServlet(ctx, "registerServlet", new RegisterServlet(repository)).addMapping("/api/register");
        Tomcat.addServlet(ctx, "loginServlet", new LoginServlet(chatRoom, repository)).addMapping("/api/login");
        Tomcat.addServlet(ctx, "meServlet", new MeServlet(repository)).addMapping("/api/me");
        Tomcat.addServlet(ctx, "webSocketStatusServlet", new WebSocketStatusServlet(chatRoom))
                .addMapping("/api/websocket-status");
        Tomcat.addServlet(ctx, "avatarServlet", new AvatarServlet(chatRoom)).addMapping("/api/me/avatar");
        Tomcat.addServlet(ctx, "passwordServlet", new PasswordServlet(repository))
                .addMapping("/api/me/password");
        Tomcat.addServlet(ctx, "logoutServlet", new LogoutServlet()).addMapping("/api/logout");
        Tomcat.addServlet(ctx, "usersServlet", new UsersServlet(chatRoom)).addMapping("/api/users");
        Tomcat.addServlet(ctx, "messagesServlet", new MessagesServlet(chatRoom, assistant))
                .addMapping("/api/messages");
        Wrapper imageMessageServlet = Tomcat.addServlet(ctx, "imageMessageServlet",
                new ImageMessageServlet(chatRoom));
        imageMessageServlet.setMultipartConfigElement(new MultipartConfigElement(
                imageStorage.directory().toString(), ImageMessageServlet.MAX_IMAGE_BYTES,
                ImageMessageServlet.MAX_IMAGE_BYTES + 64 * 1024, 1024 * 1024));
        imageMessageServlet.addMapping("/api/messages/image");
        Tomcat.addServlet(ctx, "imageServlet", new ImageServlet(repository, imageStorage))
                .addMapping("/api/images/*");
        Tomcat.addServlet(ctx, "adminMessagesServlet", new AdminMessagesServlet(chatRoom))
                .addMapping("/api/super-admin/messages/*");
        Tomcat.addServlet(ctx, "adminUsersServlet", new AdminUsersServlet(chatRoom, repository))
                .addMapping("/api/admin/users/*");
        Tomcat.addServlet(ctx, "roomSettingsServlet", new RoomSettingsServlet(chatRoom))
                .addMapping("/api/admin/room");
        Tomcat.addServlet(ctx, "roomServlet", new RoomServlet(chatRoom))
                .addMapping("/api/room");
        Tomcat.addServlet(ctx, "sensitiveWordsServlet", new SensitiveWordsServlet(chatRoom))
                .addMapping("/api/super-admin/sensitive-words");
        Tomcat.addServlet(ctx, "statisticsServlet", new StatisticsServlet(repository))
                .addMapping("/api/super-admin/statistics");
        Wrapper aiSettingsServlet = Tomcat.addServlet(ctx, "aiSettingsServlet",
                new AiSettingsServlet(configStore, assistant, chatRoom));
        aiSettingsServlet.addMapping("/api/super-admin/ai/settings");
        aiSettingsServlet.addMapping("/api/super-admin/ai/settings/test");
        Tomcat.addServlet(ctx, "aiStatusServlet", new AiStatusServlet(assistant))
                .addMapping("/api/ai/status");

        tomcat.start();
        ChatServer server = new ChatServer(tomcat, baseDirectory, assistant, repository, chatRoom);
        server.port = tomcat.getConnector().getLocalPort();
        return server;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public ChatRoom chatRoom() {
        return chatRoom;
    }

    public ChatRepository repository() {
        return repository;
    }

    public void await() {
        tomcat.getServer().await();
    }

    @Override
    public void close() {
        assistant.close();
        chatRoom.close();
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (Exception ignored) {
            // Best-effort shutdown for tests.
        } finally {
            ChatRoomHolder.set(null);
            deleteRecursively(baseDirectory);
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
