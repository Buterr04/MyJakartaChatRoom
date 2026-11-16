package site.buterr.chatroom.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;

class ChatRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void registeredAccountCanAuthenticateAfterRepositoryReopens() {
        Path database = temporaryDirectory.resolve("chat.db");
        ChatRepository repository = new ChatRepository(database);
        repository.register("alice", "Alice", "correct-password");

        ChatRepository reopened = new ChatRepository(database);
        Account authenticated = reopened.authenticate("ALICE", "correct-password").orElseThrow();
        assertEquals("Alice", authenticated.displayName());
        assertEquals(Role.ADMIN, authenticated.role());
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

    @Test
    void laterAccountsAreUsersAndMuteStateSurvivesReopen() {
        Path database = temporaryDirectory.resolve("chat.db");
        ChatRepository repository = new ChatRepository(database);
        repository.register("admin", "Admin", "correct-password");
        repository.register("alice", "Alice", "correct-password");

        long mutedUntil = System.currentTimeMillis() + 60_000;
        Account updated = repository.setMutedUntil("ALICE", mutedUntil).orElseThrow();
        assertEquals(Role.USER, updated.role());
        assertEquals(mutedUntil, updated.mutedUntil());

        ChatRepository reopened = new ChatRepository(database);
        assertEquals(mutedUntil,
                reopened.authenticate("alice", "correct-password").orElseThrow().mutedUntil());
        assertEquals(0, reopened.setMutedUntil("alice", 0).orElseThrow().mutedUntil());
        assertTrue(reopened.setMutedUntil("missing", mutedUntil).isEmpty());
    }

    @Test
    void deleteRemovesChatMessagesButNotSystemMessages() {
        Path database = temporaryDirectory.resolve("chat.db");
        ChatRepository repository = new ChatRepository(database);
        Message system = repository.saveMessage("system", null, null, "joined", 1, 10);
        Message chat = repository.saveMessage("chat", "alice", "Alice", "first", 2, 10);

        assertFalse(repository.deleteMessage(system.id()));
        assertTrue(repository.deleteMessage(chat.id()));
        assertFalse(repository.deleteMessage(chat.id()));
        assertEquals(1, repository.loadRecentMessages(10).size());
        assertEquals("joined", repository.loadRecentMessages(10).get(0).content());
    }

    @Test
    void legacyDatabaseIsMigratedWithoutLosingAccountsOrMessages() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE accounts (
                        username TEXT PRIMARY KEY COLLATE NOCASE,
                        display_name TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT (unixepoch() * 1000)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL CHECK(type IN ('chat', 'system')),
                        username TEXT,
                        display_name TEXT,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO accounts(username, display_name, password_hash, created_at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, "alice");
                insert.setString(2, "Alice");
                insert.setString(3, PasswordHasher.hash("correct-password"));
                insert.setLong(4, 1);
                insert.executeUpdate();
            }
            statement.executeUpdate("INSERT INTO messages(type, content, created_at) VALUES ('system', 'legacy', 1)");
        }

        ChatRepository migrated = new ChatRepository(database);
        Account account = migrated.authenticate("alice", "correct-password").orElseThrow();
        assertEquals(Role.ADMIN, account.role());
        assertEquals(0, account.mutedUntil());
        assertEquals("legacy", migrated.loadRecentMessages(10).get(0).content());
    }
}
