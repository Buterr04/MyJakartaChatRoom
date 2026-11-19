package site.buterr.chatroom.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class HttpAuthIT extends IntegrationTestSupport {

    @Test
    void publicAndAuthenticatedRoutesEnforceAuthFilter() throws Exception {
        TestHttp anonymous = anonymous();
        assertEquals(401, anonymous.get("/api/me").statusCode());
        assertEquals(401, anonymous.get("/api/users").statusCode());
        assertEquals(200, anonymous.get("/api/room").statusCode());

        TestHttp alice = registerAndLogin("alice", "password123");
        assertEquals(200, alice.get("/api/me").statusCode());
        assertEquals(200, alice.get("/api/ai/status").statusCode());
        assertFalse(alice.json(alice.get("/api/ai/status")).path("enabled").asBoolean());
    }

    @Test
    void normalUserCannotUseAdminOrSuperAdminRoutes() throws Exception {
        TestHttp alice = registerAndLogin("alice", "password123");
        assertEquals(403, alice.get("/api/admin/users").statusCode());
        assertEquals(403, alice.get("/api/super-admin/statistics").statusCode());
        assertEquals(403, alice.get("/api/super-admin/sensitive-words").statusCode());
        assertEquals(403, alice.put("/api/admin/room", Map.of("name", "非法", "icon", "💬")).statusCode());
    }

    @Test
    void superAdminCanUseAdminAndStatisticsRoutes() throws Exception {
        TestHttp admin = login("Admin", "admin");
        assertEquals(200, admin.get("/api/admin/users").statusCode());
        assertEquals(200, admin.get("/api/super-admin/statistics?from=2026-11-01&to=2026-11-19&granularity=day").statusCode());
        assertEquals(200, admin.get("/api/super-admin/sensitive-words").statusCode());
        assertTrue(admin.json(admin.get("/api/super-admin/statistics")).has("today"));
    }

    @Test
    void avatarLookupRequiresLoginAndReturnsKnownAvatar() throws Exception {
        assertEquals(401, anonymous().get("/api/avatars?usernames=alice").statusCode());

        TestHttp alice = registerAndLogin("alice", "password123");
        String avatar = "data:image/png;base64,AAAA";
        server.repository().setAvatar("alice", avatar);

        HttpResponse<String> response = alice.get("/api/avatars?usernames=ALICE,bob");
        assertEquals(200, response.statusCode());
        JsonNode avatars = JSON.readTree(response.body()).path("avatars");
        String avatarKey = avatars.has("alice") ? "alice" : "Alice";
        assertEquals(avatar, avatars.path(avatarKey).asText());
        assertTrue(avatars.path("bob").isMissingNode() || avatars.path("bob").isNull());
    }

    @Test
    void adminCanUpdateRoomAndBlankNameFallsBackToDefault() throws Exception {
        TestHttp admin = login("Admin", "admin");
        HttpResponse<String> updated = admin.put("/api/admin/room",
                Map.of("name", "联调房间", "icon", "🌙"));
        assertEquals(200, updated.statusCode());
        assertEquals("联调房间", admin.json(updated).path("room").path("name").asText());

        HttpResponse<String> blank = admin.put("/api/admin/room",
                Map.of("name", "", "icon", "🌙"));
        assertEquals(200, blank.statusCode());
        assertEquals("聊天室", admin.json(blank).path("room").path("name").asText());

        HttpResponse<String> publicRead = anonymous().get("/api/room");
        assertEquals(200, publicRead.statusCode());
        assertEquals("聊天室", JSON.readTree(publicRead.body()).path("room").path("name").asText());
    }
}
