package site.buterr.chatroom.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.AiSettings;
import site.buterr.chatroom.model.ChatImage;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;

/** SQLite persistence for registered accounts and the permanent chat history. */
public final class ChatRepository {

    private final String jdbcUrl;

    public ChatRepository(Path databaseFile) {
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        initialize();
    }

    public void register(String username, String displayName, String password) {
        String sql = """
                INSERT INTO accounts(username, display_name, password_hash, role)
                VALUES (?, ?, ?, 'USER')
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, displayName);
            statement.setString(3, PasswordHasher.hash(password));
            statement.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 19
                    || e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed")) {
                throw new DuplicateAccountException(username);
            }
            throw new IllegalStateException("无法保存账户", e);
        }
    }

    public Optional<Account> authenticate(String username, String password) {
        String sql = """
                SELECT username, display_name, password_hash, role, muted_until, avatar
                FROM accounts WHERE username = ? COLLATE BINARY
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !PasswordHasher.matches(password, result.getString("password_hash"))) {
                    return Optional.empty();
                }
                return Optional.of(readAccount(result));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法查询账户", e);
        }
    }

    public void markLogin(String username) {
        String sql = "UPDATE accounts SET last_login_at = ? WHERE username = ? COLLATE NOCASE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, username);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("无法记录登录时间", e);
        }
    }

    public Optional<Account> findAccount(String username) {
        try (Connection connection = connect()) {
            return findAccount(connection, username);
        } catch (SQLException e) {
            throw new IllegalStateException("无法查询账户", e);
        }
    }

    public List<Account> listAccounts() {
        String sql = """
                SELECT username, display_name, role, muted_until, avatar
                FROM accounts ORDER BY created_at, username
                """;
        List<Account> accounts = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                accounts.add(readAccount(result));
            }
            return accounts;
        } catch (SQLException e) {
            throw new IllegalStateException("无法查询账户列表", e);
        }
    }

    public Optional<Account> setRole(String username, Role role) {
        String sql = "UPDATE accounts SET role = ? WHERE username = ? COLLATE NOCASE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, role.name());
            statement.setString(2, username);
            if (statement.executeUpdate() == 0) {
                return Optional.empty();
            }
            return findAccount(connection, username);
        } catch (SQLException e) {
            throw new IllegalStateException("无法更新账户角色", e);
        }
    }

    public Optional<Account> setAvatar(String username, String avatar) {
        String sql = "UPDATE accounts SET avatar = ? WHERE username = ? COLLATE NOCASE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, avatar);
            statement.setString(2, username);
            if (statement.executeUpdate() == 0) {
                return Optional.empty();
            }
            return findAccount(connection, username);
        } catch (SQLException e) {
            throw new IllegalStateException("无法更新头像", e);
        }
    }

    public boolean changePassword(String username, String currentPassword, String newPassword) {
        String selectSql = """
                SELECT password_hash
                FROM accounts WHERE username = ? COLLATE NOCASE
                """;
        String updateSql = "UPDATE accounts SET password_hash = ? WHERE username = ? COLLATE NOCASE";
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                    statement.setString(1, username);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()
                                || !PasswordHasher.matches(currentPassword, result.getString("password_hash"))) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setString(1, PasswordHasher.hash(newPassword));
                    statement.setString(2, username);
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法更新密码", e);
        }
    }

    public Optional<Account> setMutedUntil(String username, long mutedUntil) {
        if (mutedUntil < 0) {
            throw new IllegalArgumentException("禁言截止时间不能为负数");
        }
        String sql = "UPDATE accounts SET muted_until = ? WHERE username = ? COLLATE NOCASE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, mutedUntil);
            statement.setString(2, username);
            if (statement.executeUpdate() == 0) {
                return Optional.empty();
            }
            return findAccount(connection, username);
        } catch (SQLException e) {
            throw new IllegalStateException("无法更新禁言状态", e);
        }
    }

    public boolean deleteMessage(long id) {
        String sql = "DELETE FROM messages WHERE id = ? AND type = 'chat'";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("无法删除消息", e);
        }
    }

    public List<Message> loadRecentMessages(int limit) {
        String sql = """
                SELECT id, type, username, display_name, content, created_at,
                       image_id, image_name, image_type, image_size
                FROM messages ORDER BY id DESC LIMIT ?
                """;
        List<Message> reversed = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reversed.add(readMessage(result));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法加载聊天记录", e);
        }
        List<Message> messages = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            messages.add(reversed.get(i));
        }
        return messages;
    }

    public Message saveMessage(String type, String username, String displayName, String content,
            long createdAt) {
        return saveMessage(type, username, displayName, content, createdAt, null);
    }

    public List<Message> loadMessagesBefore(long beforeId, int limit) {
        String sql = """
                SELECT id, type, username, display_name, content, created_at,
                       image_id, image_name, image_type, image_size
                FROM messages WHERE id < ? ORDER BY id DESC LIMIT ?
                """;
        List<Message> reversed = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, beforeId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reversed.add(readMessage(result));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法加载更早的聊天记录", e);
        }
        List<Message> messages = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            messages.add(reversed.get(i));
        }
        return messages;
    }

    public List<Message> loadMessagesAfter(long afterId, int limit) {
        String sql = """
                SELECT id, type, username, display_name, content, created_at,
                       image_id, image_name, image_type, image_size
                FROM messages WHERE id > ? ORDER BY id ASC LIMIT ?
                """;
        List<Message> messages = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(readMessage(result));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法加载聊天记录", e);
        }
        return messages;
    }

    public Optional<Message> findMessage(long id) {
        String sql = """
                SELECT id, type, username, display_name, content, created_at,
                       image_id, image_name, image_type, image_size
                FROM messages WHERE id = ?
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readMessage(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法读取聊天消息", e);
        }
    }

    public Message saveMessage(String type, String username, String displayName, String content,
            long createdAt, ChatImage image) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    """
                    INSERT INTO messages(type, username, display_name, content, created_at,
                                         image_id, image_name, image_type, image_size)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, type);
                insert.setString(2, username);
                insert.setString(3, displayName);
                insert.setString(4, content);
                insert.setLong(5, createdAt);
                insert.setString(6, image == null ? null : image.id());
                insert.setString(7, image == null ? null : image.originalName());
                insert.setString(8, image == null ? null : image.mediaType());
                if (image == null) {
                    insert.setNull(9, Types.BIGINT);
                } else {
                    insert.setLong(9, image.size());
                }
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("消息未生成 id");
                    }
                    long id = keys.getLong(1);
                    connection.commit();
                    return new Message(id, type, username, displayName, content, createdAt, image);
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法保存消息", e);
        }
    }

    public Optional<ChatImage> findImage(String id) {
        String sql = """
                SELECT image_id, image_name, image_type, image_size
                FROM messages WHERE image_id = ? LIMIT 1
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readImage(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法查询聊天图片", e);
        }
    }

    public MessageStats loadMessageStats(LocalDate from, LocalDate to, ZoneId zone) {
        long fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli();
        long toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        LocalDate today = LocalDate.now(zone);
        long todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        try (Connection connection = connect()) {
            long todayCount;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE type = 'chat' AND created_at >= ? AND created_at < ?")) {
                statement.setLong(1, todayStart);
                statement.setLong(2, todayEnd);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    todayCount = result.getLong(1);
                }
            }

            long totalCount;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE type = 'chat'");
                    ResultSet result = statement.executeQuery()) {
                result.next();
                totalCount = result.getLong(1);
            }

            long todayLoginCount;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM accounts WHERE last_login_at >= ? AND last_login_at < ?")) {
                statement.setLong(1, todayStart);
                statement.setLong(2, todayEnd);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    todayLoginCount = result.getLong(1);
                }
            }

            long totalUsers;
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM accounts");
                    ResultSet result = statement.executeQuery()) {
                result.next();
                totalUsers = result.getLong(1);
            }

            Map<String, Long> dailyCounts = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS total
                    FROM messages
                    WHERE type = 'chat' AND created_at >= ? AND created_at < ?
                    GROUP BY day
                    ORDER BY day
                    """)) {
                statement.setLong(1, fromMillis);
                statement.setLong(2, toMillis);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        dailyCounts.put(result.getString("day"), result.getLong("total"));
                    }
                }
            }
            List<DailyCount> daily = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                String key = date.toString();
                daily.add(new DailyCount(key, dailyCounts.getOrDefault(key, 0L)));
            }

            List<UserMessageCount> activeUsers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT username, display_name, COUNT(*) AS total
                    FROM messages
                    WHERE type = 'chat' AND username IS NOT NULL
                      AND created_at >= ? AND created_at < ?
                    GROUP BY username, display_name
                    ORDER BY total DESC, username
                    LIMIT 10
                    """)) {
                statement.setLong(1, todayStart);
                statement.setLong(2, todayEnd);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        activeUsers.add(new UserMessageCount(result.getString("username"),
                                result.getString("display_name"), result.getLong("total")));
                    }
                }
            }
            return new MessageStats(todayCount, totalCount, activeUsers, daily, todayLoginCount, totalUsers);
        } catch (SQLException e) {
            throw new IllegalStateException("无法统计消息", e);
        }
    }

    public record DailyCount(String date, long count) {
    }

    public record UserMessageCount(String username, String displayName, long count) {
    }

    public record MessageStats(long todayCount, long totalCount, List<UserMessageCount> activeUsers,
            List<DailyCount> daily, long todayLoginCount, long totalUsers) {
    }

    /** Legacy data for the one-time migration to local JSON files. */
    public Optional<AiSettings> loadLegacyAiSettings() {
        String sql = """
                SELECT base_url, api_key, model, enabled, system_prompt, updated_at
                FROM ai_settings WHERE id = 1
                """;
        try (Connection connection = connect()) {
            if (!tableExists(connection, "ai_settings")) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AiSettings(result.getString("base_url"), result.getString("api_key"),
                        result.getString("model"), result.getInt("enabled") == 1,
                        result.getString("system_prompt"), result.getLong("updated_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法迁移 AI 配置", e);
        }
    }

    /** Legacy data for the one-time migration to local JSON files. */
    public List<String> loadLegacySensitiveWords() {
        String sql = "SELECT word FROM sensitive_words ORDER BY word COLLATE NOCASE";
        try (Connection connection = connect()) {
            if (!tableExists(connection, "sensitive_words")) {
                return List.of();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet result = statement.executeQuery()) {
                List<String> words = new ArrayList<>();
                while (result.next()) {
                    words.add(result.getString("word"));
                }
                return words;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法迁移敏感词", e);
        }
    }

    /** Called after configuration has been migrated to local files. */
    public void dropLegacyConfigTables() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS ai_settings");
            statement.executeUpdate("DROP TABLE IF EXISTS sensitive_words");
        } catch (SQLException e) {
            throw new IllegalStateException("无法清理旧配置表", e);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        String sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        username TEXT PRIMARY KEY COLLATE NOCASE,
                        display_name TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT (unixepoch() * 1000),
                        role TEXT NOT NULL DEFAULT 'USER' CHECK(role IN ('USER', 'ADMIN')),
                        muted_until INTEGER NOT NULL DEFAULT 0,
                        avatar TEXT,
                        last_login_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL CHECK(type IN ('chat', 'system')),
                        username TEXT,
                        display_name TEXT,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        image_id TEXT UNIQUE,
                        image_name TEXT,
                        image_type TEXT,
                        image_size INTEGER
                    )
                    """);
            migrateAccounts(connection);
            migrateMessages(connection);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_type_created_at ON messages(type, created_at)");
            ensureAdministrator(connection);
            ensureDefaultAdministrator(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("无法初始化 SQLite 数据库", e);
        }
    }

    private void migrateAccounts(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(accounts)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        try (Statement statement = connection.createStatement()) {
            if (!columns.contains("role")) {
                statement.executeUpdate(
                        "ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT 'USER' CHECK(role IN ('USER', 'ADMIN'))");
            }
            if (!columns.contains("muted_until")) {
                statement.executeUpdate("ALTER TABLE accounts ADD COLUMN muted_until INTEGER NOT NULL DEFAULT 0");
            }
            if (!columns.contains("avatar")) {
                statement.executeUpdate("ALTER TABLE accounts ADD COLUMN avatar TEXT");
            }
            if (!columns.contains("last_login_at")) {
                statement.executeUpdate("ALTER TABLE accounts ADD COLUMN last_login_at INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    private void ensureDefaultAdministrator(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO accounts(username, display_name, password_hash, role)
                VALUES ('Admin', 'Admin', ?, 'ADMIN')
                ON CONFLICT(username) DO UPDATE SET role = 'ADMIN'
                """)) {
            statement.setString(1, PasswordHasher.hash("admin"));
            statement.executeUpdate();
        }
    }

    private void migrateMessages(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(messages)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        try (Statement statement = connection.createStatement()) {
            if (!columns.contains("image_id")) {
                statement.executeUpdate("ALTER TABLE messages ADD COLUMN image_id TEXT");
            }
            if (!columns.contains("image_name")) {
                statement.executeUpdate("ALTER TABLE messages ADD COLUMN image_name TEXT");
            }
            if (!columns.contains("image_type")) {
                statement.executeUpdate("ALTER TABLE messages ADD COLUMN image_type TEXT");
            }
            if (!columns.contains("image_size")) {
                statement.executeUpdate("ALTER TABLE messages ADD COLUMN image_size INTEGER");
            }
            statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_image_id ON messages(image_id)");
        }
    }

    private void ensureAdministrator(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE accounts SET role = 'ADMIN'
                    WHERE username = (
                        SELECT username FROM accounts ORDER BY created_at, username LIMIT 1
                    )
                    AND NOT EXISTS (SELECT 1 FROM accounts WHERE role = 'ADMIN')
                    """);
        }
    }

    private Optional<Account> findAccount(Connection connection, String username) throws SQLException {
        String sql = """
                SELECT username, display_name, role, muted_until, avatar
                FROM accounts WHERE username = ? COLLATE NOCASE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAccount(result)) : Optional.empty();
            }
        }
    }

    private Account readAccount(ResultSet result) throws SQLException {
        return new Account(result.getString("username"), result.getString("display_name"),
                Role.valueOf(result.getString("role")), result.getLong("muted_until"), result.getString("avatar"));
    }

    private Message readMessage(ResultSet result) throws SQLException {
        return new Message(result.getLong("id"), result.getString("type"), result.getString("username"),
                result.getString("display_name"), result.getString("content"), result.getLong("created_at"),
                readImage(result));
    }

    private ChatImage readImage(ResultSet result) throws SQLException {
        String id = result.getString("image_id");
        return id == null ? null
                : new ChatImage(id, result.getString("image_name"), result.getString("image_type"),
                        result.getLong("image_size"));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
