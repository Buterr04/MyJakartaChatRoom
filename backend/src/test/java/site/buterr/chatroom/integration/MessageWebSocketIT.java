package site.buterr.chatroom.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class MessageWebSocketIT extends IntegrationTestSupport {

    @Test
    void restMessageIsPushedToConnectedWebSocket() throws Exception {
        TestHttp alice = registerAndLogin("alice", "password123");
        WsProbe ws = WsProbe.connect(server, alice);
        try {
            ws.awaitType("online", 10);

            HttpResponse<String> sent = alice.post("/api/messages", Map.of("content", "集成测试消息"));
            assertEquals(201, sent.statusCode());

            JsonNode packet = ws.awaitType("message", 10);
            assertEquals("集成测试消息", packet.path("data").path("content").asText());
            assertEquals("alice", packet.path("data").path("username").asText());
        } finally {
            ws.close();
        }
    }

    @Test
    void restSendWithoutWebSocketIsRejected() throws Exception {
        TestHttp alice = registerAndLogin("alice", "password123");
        HttpResponse<String> sent = alice.post("/api/messages", Map.of("content", "没有 WS"));
        assertEquals(409, sent.statusCode());
    }

    @Test
    void onlinePacketReportsConnectedUser() throws Exception {
        TestHttp alice = registerAndLogin("alice", "password123");
        WsProbe ws = WsProbe.connect(server, alice);
        try {
            JsonNode online = ws.awaitType("online", 10);
            assertEquals(1, online.path("data").path("count").asInt());
            assertTrue(online.path("data").path("users").toString().contains("alice"));
        } finally {
            ws.close();
        }
    }
}
