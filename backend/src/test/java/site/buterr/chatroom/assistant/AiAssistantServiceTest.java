package site.buterr.chatroom.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.websocket.Session;

import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.User;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.store.ChatRoom;

class AiAssistantServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicReference<String> authorization = new AtomicReference<>("");
    private final AtomicReference<String> requestPath = new AtomicReference<>("");
    private final AtomicInteger requestCount = new AtomicInteger();

    private HttpServer server;
    private ChatRepository repository;
    private ChatRoom chatRoom;
    private AiAssistantService assistant;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"choices\":[{\"message\":{\"content\":\"测试回复\"}}]}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        repository = new ChatRepository(temporaryDirectory.resolve("ai.db"));
        chatRoom = new ChatRoom(200, repository);
        assistant = new AiAssistantService(repository, chatRoom);
    }

    @AfterEach
    void tearDown() {
        if (assistant != null) {
            assistant.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mentionCreatesPersistedAssistantReplyWithoutBlockingChatRoom() throws Exception {
        saveSettings(true);
        connect(chatRoom, chatRoom.login("alice"));
        Message question = chatRoom.sendChat("alice", "@AI 你好");

        assistant.handleUserMessage(question);

        Message reply = awaitAssistantReply();
        assertEquals("测试回复", reply.content());
        assertEquals("AI助手", reply.displayName());
        assertEquals(null, reply.username());
        assertEquals("/v1/chat/completions", requestPath.get());
        assertEquals("Bearer test-secret", authorization.get());
        assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
        assertTrue(requestBody.get().contains("alice：@AI 你好"));
        assertTrue(new ChatRepository(temporaryDirectory.resolve("ai.db")).loadRecentMessages(10)
                .stream().anyMatch(message -> message.id() == reply.id()));
    }

    @Test
    void connectionTestUsesSavedSettingsEvenWhenAssistantIsDisabled() throws Exception {
        saveSettings(false);

        String reply = assistant.testConnection();

        assertEquals("测试回复", reply);
        assertEquals("/v1/chat/completions", requestPath.get());
        assertTrue(requestBody.get().contains("请简短回复：连接成功"));
        assertTrue(requestBody.get().contains("友善地简洁回答"));
    }

    @Test
    void disabledAssistantDoesNotCallUpstream() throws Exception {
        saveSettings(false);
        connect(chatRoom, chatRoom.login("alice"));
        Message question = chatRoom.sendChat("alice", "@AI 你好");

        assistant.handleUserMessage(question);

        assertTrue(awaitAssistantReply().content().contains("尚未启用"));
        assertEquals(0, requestCount.get());
    }

    @Test
    void upstreamFailureProducesFriendlyMessageWithoutLeakingSecrets() throws Exception {
        saveSettings(true);
        responseStatus = 401;
        responseBody = "{\"error\":\"test-secret must not leak\"}";
        connect(chatRoom, chatRoom.login("alice"));
        Message question = chatRoom.sendChat("alice", "@AI 你好");

        assistant.handleUserMessage(question);

        Message reply = awaitAssistantReply();
        assertTrue(reply.content().contains("认证失败"));
        assertFalse(reply.content().contains("test-secret"));
        assertFalse(reply.content().contains("must not leak"));
    }

    private void saveSettings(boolean enabled) {
        repository.saveAiSettings("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/v1", "test-secret", "test-model", enabled, "友善地简洁回答");
    }

    private static Session webSocket() {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == arguments[0];
                    }
                    if (method.getName().equals("toString")) {
                        return "WebSocket@" + System.identityHashCode(proxy);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    private static void connect(ChatRoom room, User user) {
        room.bindWs(user, "session-" + user.username(), webSocket());
    }

    private Message awaitAssistantReply() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<Message> replies = chatRoom.historyAfter(0).stream()
                    .filter(message -> "AI助手".equals(message.displayName()))
                    .toList();
            if (!replies.isEmpty()) {
                return replies.get(replies.size() - 1);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待 AI 回复超时");
    }
}
