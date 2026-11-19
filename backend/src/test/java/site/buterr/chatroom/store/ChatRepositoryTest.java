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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.ChatImage;
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
        Account authenticated = reopened.authenticate("alice", "correct-password").orElseThrow();
        assertEquals("Alice", authenticated.displayName());
        assertEquals(Role.USER, authenticated.role());
        assertTrue(reopened.authenticate("alice", "wrong-password").isEmpty());
        // Username uniqueness is NOCASE, but login itself is case-sensitive.
        assertTrue(reopened.authenticate("ALICE", "correct-password").isEmpty());
        assertThrows(DuplicateAccountException.class,
                () -> reopened.register("Alice", "Another Alice", "another-password"));
    }

    @Test
    void messagesSurviveReopenAndAreNotTrimmed() {
        Path database = temporaryDirectory.resolve("chat.db");
        ChatRepository repository = new ChatRepository(database);
        repository.saveMessage("system", null, null, "joined", 1);
        repository.saveMessage("chat", "alice", "Alice", "first", 2);
        repository.saveMessage("chat", "alice", "Alice", "second", 3);

        var messages = new ChatRepository(database).loadRecentMessages(10);
        assertEquals(3, messages.size());
        assertEquals("joined", messages.get(0).content());
        assertEquals("first", messages.get(1).content());
        assertEquals("second", messages.get(2).content());
        assertEquals("alice", messages.get(1).username());
        assertEquals("Alice", messages.get(1).displayName());
    }

    @Test
    void messagePagesUseKeysetCursors() {
        Path database = temporaryDirectory.resolve("pages.db");
        ChatRepository repository = new ChatRepository(database);
        for (int i = 1; i <= 5; i++) {
            repository.saveMessage("chat", "alice", "Alice", "m" + i, i);
        }

        var latest = repository.loadRecentMessages(2);
        assertEquals(List.of("m4", "m5"), latest.stream().map(message -> message.content()).toList());

        var before = repository.loadMessagesBefore(4, 2);
        assertEquals(List.of("m2", "m3"), before.stream().map(message -> message.content()).toList());

        var after = repository.loadMessagesAfter(3, 2);
        assertEquals(List.of("m4", "m5"), after.stream().map(message -> message.content()).toList());
        assertEquals("m3", repository.findMessage(3).orElseThrow().content());
    }

    @Test
    void messageHistoryIsNotTrimmedAtTwoHundred() {
        Path database = temporaryDirectory.resolve("all-messages.db");
        ChatRepository repository = new ChatRepository(database);
        for (int i = 1; i <= 205; i++) {
            repository.saveMessage("chat", "alice", "Alice", "m" + i, i);
        }
        assertEquals(205, repository.loadRecentMessages(300).size());
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
        // Login is case-sensitive even though the uniqueness check is NOCASE.
        assertTrue(new ChatRepository(database).authenticate("ADMIN", "admin").isEmpty());
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
        Message system = repository.saveMessage("system", null, null, "joined", 1);
        Message chat = repository.saveMessage("chat", "alice", "Alice", "first", 2);

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

        repository.saveMessage("chat", "alice", "Alice", "", 1, image);

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
        migrated.saveMessage("chat", "alice", "Alice", "", 2, image);
        assertEquals(image, migrated.findImage(image.id()).orElseThrow());
    }

    @Test
    void messageStatsExcludeSystemAndGroupByDay() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        long todayNoon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
        long yesterdayNoon = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
        ChatRepository repository = new ChatRepository(temporaryDirectory.resolve("stats.db"));
        repository.saveMessage("chat", "alice", "Alice", "a1", todayNoon);
        repository.saveMessage("chat", "alice", "Alice", "a2", todayNoon + 1);
        repository.saveMessage("chat", "bob", "Bob", "b1", todayNoon + 2);
        repository.saveMessage("system", null, null, "joined", todayNoon + 3);
        repository.saveMessage("chat", "alice", "Alice", "old", yesterdayNoon);

        ChatRepository.MessageStats stats = repository.loadMessageStats(today.minusDays(1), today, zone);
        assertEquals(3, stats.todayCount());
        assertEquals(4, stats.totalCount());
        assertEquals(2, stats.daily().size());
        assertEquals(1, stats.daily().get(0).count());
        assertEquals(3, stats.daily().get(1).count());
        assertEquals(2, stats.activeUsers().size());
        assertEquals("alice", stats.activeUsers().get(0).username());
        assertEquals(2, stats.activeUsers().get(0).count());

        repository.markLogin("Admin");
        ChatRepository.MessageStats afterLogin = repository.loadMessageStats(today.minusDays(1), today, zone);
        assertEquals(1, afterLogin.todayLoginCount());
        assertEquals(1, afterLogin.totalUsers());
    }

    @Test
    void legacyConfigTablesCanBeReadAndDropped() throws Exception {
        Path database = temporaryDirectory.resolve("legacy-config.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE ai_settings (id INTEGER PRIMARY KEY, base_url TEXT, api_key TEXT, model TEXT, enabled INTEGER, system_prompt TEXT, updated_at INTEGER)");
            statement.executeUpdate("CREATE TABLE sensitive_words (word TEXT PRIMARY KEY, created_at INTEGER)");
            statement.executeUpdate("INSERT INTO ai_settings VALUES (1, 'https://legacy.example/v1', 'legacy-key', 'legacy-model', 1, 'legacy prompt', 42)");
            statement.executeUpdate("INSERT INTO sensitive_words VALUES ('旧敏感词', 42)");
        }
        ChatRepository repository = new ChatRepository(database);
        assertEquals("https://legacy.example/v1", repository.loadLegacyAiSettings().orElseThrow().baseUrl());
        assertEquals(List.of("旧敏感词"), repository.loadLegacySensitiveWords());

        repository.dropLegacyConfigTables();
        assertTrue(repository.loadLegacyAiSettings().isEmpty());
        assertTrue(repository.loadLegacySensitiveWords().isEmpty());
    }
}
