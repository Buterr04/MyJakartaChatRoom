package site.buterr.chatroom.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class SingleSessionIT extends IntegrationTestSupport {

    @Test
    void duplicateLoginWithoutForceIsRejected() throws Exception {
        TestHttp alice = registerAndLogin("alice", "password123");
        WsProbe ws = WsProbe.connect(server, alice);
        try {
            ws.awaitType("online", 10);
            HttpResponse<String> second = anonymous().login("alice", "password123", false);
            assertEquals(409, second.statusCode());
        } finally {
            ws.close();
        }
    }

    @Test
    void forceLoginRevokesOldHttpSessionAndWebSocket() throws Exception {
        TestHttp oldClient = registerAndLogin("alice", "password123");
        WsProbe oldWs = WsProbe.connect(server, oldClient);
        oldWs.awaitType("online", 10);

        TestHttp newClient = anonymous();
        HttpResponse<String> second = newClient.login("alice", "password123", true);
        assertEquals(200, second.statusCode());

        assertEquals(401, oldClient.get("/api/me").statusCode());
        assertEquals(1008, oldWs.awaitCloseCode(10));

        assertEquals(200, newClient.get("/api/me").statusCode());
    }
}
