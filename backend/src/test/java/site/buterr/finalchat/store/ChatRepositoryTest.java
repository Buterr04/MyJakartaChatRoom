package site.buterr.finalchat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void registeredAccountCanAuthenticateAfterRepositoryReopens() {
        Path database = temporaryDirectory.resolve("chat.db");
        ChatRepository repository = new ChatRepository(database);
        repository.register("alice", "Alice", "correct-password");

        ChatRepository reopened = new ChatRepository(database);
        assertEquals("Alice", reopened.authenticate("ALICE", "correct-password").orElseThrow().displayName());
        assertTrue(reopened.authenticate("alice", "wrong-password").isEmpty());
        assertThrows(DuplicateAccountException.class,
                () -> reopened.register("Alice", "Another Alice", "another-password"));
    }

    @Test
    void messagesSurviveReopenAndAreTrimmed() {
        Path database = temporaryDirectory.resolve("chat.db");
        ChatRepository repository = new ChatRepository(database);
        repository.saveMessage("system", null, null, "joined", 1, 2);
        repository.saveMessage("chat", "alice", "Alice", "first", 2, 2);
        repository.saveMessage("chat", "alice", "Alice", "second", 3, 2);

        var messages = new ChatRepository(database).loadRecentMessages(2);
        assertEquals(2, messages.size());
        assertEquals("first", messages.get(0).content());
        assertEquals("second", messages.get(1).content());
        assertEquals("alice", messages.get(0).username());
        assertEquals("Alice", messages.get(0).displayName());
        assertFalse(messages.stream().anyMatch(message -> message.content().equals("joined")));
    }
}
