package site.buterr.chatroom.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import site.buterr.chatroom.ChatServer;

/** Shared embedded-server + HTTP/WebSocket helpers for the integration tests. */
abstract class IntegrationTestSupport {

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    protected Path tempDirectory;

    protected ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        Path staticDirectory = tempDirectory.resolve("static");
        Files.createDirectories(staticDirectory);
        server = ChatServer.start(new ChatServer.Config(
                0,
                tempDirectory.resolve("chatroom.db"),
                tempDirectory.resolve("uploads"),
                tempDirectory.resolve("config"),
                staticDirectory));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    protected TestHttp anonymous() {
        return new TestHttp(server.baseUrl());
    }

    protected TestHttp login(String username, String password) throws Exception {
        TestHttp client = anonymous();
        HttpResponse<String> response = client.login(username, password, false);
        if (response.statusCode() != 200) {
            throw new AssertionError("login failed: " + username + " -> " + response.statusCode()
                    + " " + response.body());
        }
        return client;
    }

    protected TestHttp registerAndLogin(String username, String password) throws Exception {
        TestHttp client = anonymous();
        HttpResponse<String> registered = client.post("/api/register", Map.of(
                "username", username,
                "displayName", username,
                "password", password));
        if (registered.statusCode() != 201) {
            throw new AssertionError("register failed: " + registered.statusCode() + " " + registered.body());
        }
        HttpResponse<String> loggedIn = client.login(username, password, false);
        if (loggedIn.statusCode() != 200) {
            throw new AssertionError("login failed: " + loggedIn.statusCode() + " " + loggedIn.body());
        }
        return client;
    }

    protected static final class TestHttp {
        private final HttpClient client;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final String baseUrl;

        TestHttp(String baseUrl) {
            this.baseUrl = baseUrl;
            this.client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        HttpResponse<String> login(String username, String password, boolean force) throws Exception {
            return post("/api/login", Map.of(
                    "username", username,
                    "password", password,
                    "force", force));
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        }

        HttpResponse<String> put(String path, Object body) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        }

        JsonNode json(HttpResponse<String> response) throws Exception {
            return JSON.readTree(response.body());
        }

        String cookieHeader() {
            List<String> values = new ArrayList<>();
            cookies.getCookieStore().getCookies().forEach(cookie ->
                    values.add(cookie.getName() + "=" + cookie.getValue()));
            return String.join("; ", values);
        }

        private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            return client.send(builder.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    protected static final class WsProbe implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        static WsProbe connect(ChatServer server, TestHttp http) throws Exception {
            WsProbe probe = new WsProbe();
            String cookie = http.cookieHeader();
            probe.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .header("Cookie", cookie)
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/ws/chat"), probe)
                    .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return probe;
        }

        JsonNode awaitType(String type, long timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                JsonNode packet = messages.poll(200, TimeUnit.MILLISECONDS);
                if (packet != null && type.equals(packet.path("type").asText())) {
                    return packet;
                }
            }
            throw new AssertionError("did not receive WebSocket packet: " + type);
        }

        int awaitCloseCode(long timeoutSeconds) throws Exception {
            return closeCode.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        void close() {
            if (socket != null) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test done").join();
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    messages.add(JSON.readTree(partial.toString()));
                } catch (Exception e) {
                    throw new AssertionError("invalid WebSocket JSON: " + partial, e);
                } finally {
                    partial.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }

    protected static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
