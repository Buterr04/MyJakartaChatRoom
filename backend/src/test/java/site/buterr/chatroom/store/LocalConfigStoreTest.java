package site.buterr.chatroom.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import site.buterr.chatroom.model.AiSettings;
import site.buterr.chatroom.model.RoomBranding;

class LocalConfigStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sensitiveWordsPersistInJsonAndCanBeCleared() throws Exception {
        LocalConfigStore store = new LocalConfigStore(temporaryDirectory.resolve("config"));
        assertFalse(store.loadSensitiveWords().isEmpty());
        assertTrue(store.addSensitiveWord("广告词"));
        assertFalse(store.addSensitiveWord("广告词"));

        LocalConfigStore reloaded = new LocalConfigStore(temporaryDirectory.resolve("config"));
        assertTrue(reloaded.loadSensitiveWords().contains("广告词"));
        assertTrue(Files.readString(reloaded.directory().resolve("sensitive-words.json"))
                .contains("广告词"));

        for (String word : List.copyOf(reloaded.loadSensitiveWords())) {
            assertTrue(reloaded.removeSensitiveWord(word));
        }
        assertTrue(new LocalConfigStore(temporaryDirectory.resolve("config")).loadSensitiveWords().isEmpty());
    }

    @Test
    void aiSettingsPersistInJson() throws Exception {
        Path directory = temporaryDirectory.resolve("config");
        LocalConfigStore store = new LocalConfigStore(directory);
        assertFalse(store.loadAiSettings().enabled());

        store.saveAiSettings("https://example.com/v1", "secret-key", "test-model", true, "简洁回答");

        AiSettings restored = new LocalConfigStore(directory).loadAiSettings();
        assertEquals("https://example.com/v1", restored.baseUrl());
        assertEquals("secret-key", restored.apiKey());
        assertEquals("test-model", restored.model());
        assertTrue(restored.enabled());
        assertEquals("简洁回答", restored.systemPrompt());
        assertTrue(restored.updatedAt() > 0);
        assertTrue(Files.readString(directory.resolve("ai-settings.json")).contains("secret-key"));
    }

    @Test
    void roomBrandingPersistsInJson() throws Exception {
        Path directory = temporaryDirectory.resolve("room-config");
        LocalConfigStore store = new LocalConfigStore(directory);
        assertEquals("聊天室", store.loadRoomBranding().name());
        assertEquals("💬", store.loadRoomBranding().icon());

        store.saveRoomBranding(new RoomBranding("夜聊", "🌙", 42));
        RoomBranding restored = new LocalConfigStore(directory).loadRoomBranding();
        assertEquals("夜聊", restored.name());
        assertEquals("🌙", restored.icon());
        assertEquals(42, restored.updatedAt());
    }

    @Test
    void migratesLegacyTablesOnlyOnce() throws Exception {
        Path database = temporaryDirectory.resolve("legacy.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE ai_settings (id INTEGER PRIMARY KEY, base_url TEXT, api_key TEXT, model TEXT, enabled INTEGER, system_prompt TEXT, updated_at INTEGER)");
            statement.executeUpdate("CREATE TABLE sensitive_words (word TEXT PRIMARY KEY, created_at INTEGER)");
            statement.executeUpdate("INSERT INTO ai_settings VALUES (1, 'https://legacy.example/v1', 'legacy-key', 'legacy-model', 1, 'legacy prompt', 42)");
            statement.executeUpdate("INSERT INTO sensitive_words VALUES ('旧敏感词', 42)");
        }
        ChatRepository repository = new ChatRepository(database);
        Path directory = temporaryDirectory.resolve("migrated-config");
        LocalConfigStore store = new LocalConfigStore(directory);
        store.migrateFrom(repository);
        repository.dropLegacyConfigTables();

        assertEquals(List.of("旧敏感词"), store.loadSensitiveWords());
        assertEquals("https://legacy.example/v1", store.loadAiSettings().baseUrl());
        assertTrue(repository.loadLegacySensitiveWords().isEmpty());
        assertTrue(repository.loadLegacyAiSettings().isEmpty());

        // Files already exist, so a second migrate must not overwrite admin edits.
        assertTrue(store.addSensitiveWord("新词"));
        new LocalConfigStore(directory).migrateFrom(repository);
        assertTrue(new LocalConfigStore(directory).loadSensitiveWords().contains("新词"));
    }
}
