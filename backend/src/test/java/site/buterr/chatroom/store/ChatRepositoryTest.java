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
import site.buterr.chatroom.model.AiSettings;
import site.buterr.chatroom.model.ChatImage;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.util.JsonUtil;

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
        assertEquals(Role.USER, authenticated.role());
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
        repository.register("owner", "Owner", "correct-password");
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
    void defaultAdministratorIsAlwaysAvailable() {
        Path database = temporaryDirectory.resolve("default-admin.db");
        ChatRepository repository = new ChatRepository(database);

        Account administrator = repository.authenticate("Admin", "admin").orElseThrow();
        assertEquals(Role.ADMIN, administrator.role());
        assertEquals("Admin", administrator.displayName());
        assertEquals(Role.ADMIN,
                new ChatRepository(database).authenticate("ADMIN", "admin").orElseThrow().role());
        assertThrows(DuplicateAccountException.class,
                () -> repository.register("admin", "Another Admin", "another-password"));
    }

    @Test
    void rolesAndAvatarsArePersistedAndListed() {
        Path database = temporaryDirectory.resolve("profiles.db");
        ChatRepository repository = new ChatRepository(database);
        repository.register("alice", "Alice", "correct-password");
        String avatar = "data:image/png;base64,profile-image";

        Account promoted = repository.setRole("ALICE", Role.ADMIN).orElseThrow();
        assertEquals(Role.ADMIN, promoted.role());
        Account withAvatar = repository.setAvatar("alice", avatar).orElseThrow();
        assertEquals(avatar, withAvatar.avatar());

        ChatRepository reopened = new ChatRepository(database);
        Account restored = reopened.findAccount("alice").orElseThrow();
        assertEquals(Role.ADMIN, restored.role());
        assertEquals(avatar, restored.avatar());
        assertEquals(2, reopened.listAccounts().size());
        assertEquals(Role.USER, reopened.setRole("alice", Role.USER).orElseThrow().role());
        assertEquals(null, reopened.setAvatar("alice", null).orElseThrow().avatar());
        assertTrue(reopened.setRole("missing", Role.ADMIN).isEmpty());
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndPersists() {
        Path database = temporaryDirectory.resolve("passwords.db");
        ChatRepository repository = new ChatRepository(database);
        repository.register("alice", "Alice", "correct-password");

        assertFalse(repository.changePassword("alice", "wrong-password", "new-password"));
        assertTrue(repository.authenticate("alice", "correct-password").isPresent());
        assertTrue(repository.changePassword("ALICE", "correct-password", "new-password"));

        ChatRepository reopened = new ChatRepository(database);
        assertTrue(reopened.authenticate("alice", "new-password").isPresent());
        assertTrue(reopened.authenticate("alice", "correct-password").isEmpty());
        assertFalse(reopened.changePassword("missing", "correct-password", "new-password"));
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
    void imageMetadataSurvivesRepositoryReopen() {
        Path database = temporaryDirectory.resolve("images.db");
        ChatRepository repository = new ChatRepository(database);
        ChatImage image = new ChatImage("2d57ef6d-9a50-4f5b-930f-e4f1a7252ee6",
                "pixel.png", "image/png", 68);

        repository.saveMessage("chat", "alice", "Alice", "", 1, 10, image);

        ChatRepository reopened = new ChatRepository(database);
        assertEquals(image, reopened.findImage(image.id()).orElseThrow());
        assertEquals(image, reopened.loadRecentMessages(10).get(0).image());
        assertTrue(reopened.findImage("missing").isEmpty());
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
        ChatImage image = new ChatImage("2d57ef6d-9a50-4f5b-930f-e4f1a7252ee6",
                "pixel.png", "image/png", 68);
        migrated.saveMessage("chat", "alice", "Alice", "", 2, 10, image);
        assertEquals(image, migrated.findImage(image.id()).orElseThrow());
    }

    @Test
    void aiSettingsSurviveRepositoryReopen() {
        Path database = temporaryDirectory.resolve("ai-settings.db");
        ChatRepository repository = new ChatRepository(database);
        assertFalse(repository.loadAiSettings().enabled());
        assertFalse(repository.loadAiSettings().hasApiKey());

        repository.saveAiSettings("https://example.com/v1", "secret-key", "test-model", true, "简洁回答");

        AiSettings restored = new ChatRepository(database).loadAiSettings();
        assertEquals("https://example.com/v1", restored.baseUrl());
        assertEquals("secret-key", restored.apiKey());
        assertEquals("test-model", restored.model());
        assertTrue(restored.enabled());
        assertEquals("简洁回答", restored.systemPrompt());
        assertTrue(restored.updatedAt() > 0);
        assertFalse(JsonUtil.toJson(restored).contains("secret-key"));
        assertFalse(JsonUtil.toJson(restored).contains("apiKey"));
    }
}
