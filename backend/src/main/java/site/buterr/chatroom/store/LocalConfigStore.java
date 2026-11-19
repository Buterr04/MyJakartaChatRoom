package site.buterr.chatroom.store;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import site.buterr.chatroom.model.AiSettings;
import site.buterr.chatroom.model.RoomBranding;

/**
 * Local JSON configuration for the sensitive-word list and AI settings.
 * Values are no longer stored in SQLite.
 */
public final class LocalConfigStore {

    private static final String SENSITIVE_FILE = "sensitive-words.json";
    private static final String AI_FILE = "ai-settings.json";
    private static final String ROOM_FILE = "room.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path directory;
    private final Path sensitiveFile;
    private final Path aiFile;
    private final Path roomFile;

    public LocalConfigStore(Path directory) {
        this.directory = directory.toAbsolutePath();
        this.sensitiveFile = this.directory.resolve(SENSITIVE_FILE);
        this.aiFile = this.directory.resolve(AI_FILE);
        this.roomFile = this.directory.resolve(ROOM_FILE);
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建配置目录: " + this.directory, e);
        }
    }

    /** One-time migration from the legacy SQLite tables, only when a file is absent. */
    public synchronized void migrateFrom(ChatRepository repository) {
        if (!Files.exists(sensitiveFile)) {
            List<String> legacy = repository.loadLegacySensitiveWords();
            saveSensitiveWords(legacy.isEmpty() ? SensitiveWords.DEFAULTS : legacy);
        }
        if (!Files.exists(aiFile)) {
            saveAiSettings(repository.loadLegacyAiSettings().orElse(AiSettings.empty()));
        }
    }

    public synchronized List<String> loadSensitiveWords() {
        if (!Files.exists(sensitiveFile)) {
            saveSensitiveWords(SensitiveWords.DEFAULTS);
        }
        SensitiveWordsFile file = read(sensitiveFile, SensitiveWordsFile.class);
        if (file == null || file.words() == null) {
            return List.of();
        }
        return file.words().stream()
                .filter(word -> word != null && !word.isBlank())
                .map(String::trim)
                .toList();
    }

    public synchronized boolean addSensitiveWord(String word) {
        String cleaned = clean(word);
        List<String> words = new ArrayList<>(loadSensitiveWords());
        if (words.stream().anyMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
            return false;
        }
        words.add(cleaned);
        saveSensitiveWords(words);
        return true;
    }

    public synchronized boolean removeSensitiveWord(String word) {
        String cleaned = clean(word);
        List<String> words = new ArrayList<>(loadSensitiveWords());
        boolean removed = words.removeIf(existing -> existing.equalsIgnoreCase(cleaned));
        if (removed) {
            saveSensitiveWords(words);
        }
        return removed;
    }

    private void saveSensitiveWords(List<String> words) {
        List<String> cleaned = words.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        write(sensitiveFile, new SensitiveWordsFile(cleaned));
    }

    public synchronized AiSettings loadAiSettings() {
        if (!Files.exists(aiFile)) {
            return AiSettings.empty();
        }
        AiSettingsFile file = read(aiFile, AiSettingsFile.class);
        return file == null ? AiSettings.empty() : file.toSettings();
    }

    public synchronized AiSettings saveAiSettings(AiSettings settings) {
        write(aiFile, AiSettingsFile.from(settings));
        return settings;
    }

    public synchronized AiSettings saveAiSettings(String baseUrl, String apiKey, String model,
            boolean enabled, String systemPrompt) {
        return saveAiSettings(new AiSettings(baseUrl, apiKey, model, enabled, systemPrompt,
                System.currentTimeMillis()));
    }

    public synchronized RoomBranding loadRoomBranding() {
        if (!Files.exists(roomFile)) {
            return RoomBranding.defaults();
        }
        RoomBrandingFile file = read(roomFile, RoomBrandingFile.class);
        if (file == null || file.name() == null || file.name().isBlank()) {
            return RoomBranding.defaults();
        }
        String icon = file.icon() == null || file.icon().isBlank()
                ? RoomBranding.DEFAULT_ICON
                : file.icon();
        return new RoomBranding(file.name(), icon, file.updatedAt());
    }

    public synchronized RoomBranding saveRoomBranding(RoomBranding branding) {
        write(roomFile, new RoomBrandingFile(branding.name(), branding.icon(), branding.updatedAt()));
        return branding;
    }

    public Path directory() {
        return directory;
    }

    private static String clean(String word) {
        String cleaned = word == null ? "" : word.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("敏感词不能为空");
        }
        return cleaned;
    }

    private <T> T read(Path file, Class<T> type) {
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取配置文件: " + file, e);
        }
    }

    private void write(Path file, Object value) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".config-", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法保存配置文件: " + file, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private record SensitiveWordsFile(List<String> words) {
    }

    private record RoomBrandingFile(String name, String icon, long updatedAt) {
    }

    private record AiSettingsFile(String baseUrl, String apiKey, String model, boolean enabled,
            String systemPrompt, long updatedAt) {

        private static AiSettingsFile from(AiSettings settings) {
            return new AiSettingsFile(settings.baseUrl(), settings.apiKey(), settings.model(),
                    settings.enabled(), settings.systemPrompt(), settings.updatedAt());
        }

        private AiSettings toSettings() {
            return new AiSettings(baseUrl, apiKey, model, enabled, systemPrompt, updatedAt);
        }
    }
}
