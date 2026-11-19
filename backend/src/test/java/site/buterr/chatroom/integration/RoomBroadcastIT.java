package site.buterr.chatroom.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RoomBroadcastIT extends IntegrationTestSupport {

    @Test
    void roomUpdateIsBroadcastToOnlineUser() throws Exception {
        TestHttp alice = registerAndLogin("alice", "password123");
        WsProbe ws = WsProbe.connect(server, alice);
        try {
            ws.awaitType("online", 10);

            TestHttp admin = login("Admin", "admin");
            HttpResponse<String> updated = admin.put("/api/admin/room",
                    Map.of("name", "广播测试房间", "icon", "🌙"));
            assertEquals(200, updated.statusCode());

            JsonNode packet = ws.awaitType("roomUpdated", 10);
            assertEquals("广播测试房间", packet.path("data").path("name").asText());
            assertEquals("🌙", packet.path("data").path("icon").asText());

            HttpResponse<String> publicRead = anonymous().get("/api/room");
            assertEquals("广播测试房间",
                    JSON.readTree(publicRead.body()).path("room").path("name").asText());
        } finally {
            ws.close();
        }
    }
}
