package site.buterr.chatroom.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.ChatImage;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;

/** SQLite persistence for registered accounts and the bounded chat history. */
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
                FROM accounts WHERE username = ? COLLATE NOCASE
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
            long createdAt, int maxHistory) {
        return saveMessage(type, username, displayName, content, createdAt, maxHistory, null);
    }

    public Message saveMessage(String type, String username, String displayName, String content,
            long createdAt, int maxHistory, ChatImage image) {
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
                    trimMessages(connection, maxHistory);
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
                        avatar TEXT
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

    private void trimMessages(Connection connection, int maxHistory) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT ?)")) {
            statement.setInt(1, maxHistory);
            statement.executeUpdate();
        }
    }
}
