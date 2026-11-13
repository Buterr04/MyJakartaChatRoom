package site.buterr.finalchat.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import site.buterr.finalchat.model.Account;
import site.buterr.finalchat.model.Message;

/** SQLite persistence for registered accounts and the bounded chat history. */
public final class ChatRepository {

    private final String jdbcUrl;

    public ChatRepository(Path databaseFile) {
        this.jdbcUrl = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        initialize();
    }

    public void register(String username, String displayName, String password) {
        String sql = "INSERT INTO accounts(username, display_name, password_hash) VALUES (?, ?, ?)";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, displayName);
            statement.setString(3, PasswordHasher.hash(password));
            statement.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 19 || e.getMessage().contains("UNIQUE constraint failed")) {
                throw new DuplicateAccountException(username);
            }
            throw new IllegalStateException("无法保存账户", e);
        }
    }

    public Optional<Account> authenticate(String username, String password) {
        String sql = "SELECT username, display_name, password_hash FROM accounts WHERE username = ? COLLATE NOCASE";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !PasswordHasher.matches(password, result.getString("password_hash"))) {
                    return Optional.empty();
                }
                return Optional.of(new Account(result.getString("username"), result.getString("display_name")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法查询账户", e);
        }
    }

    public List<Message> loadRecentMessages(int limit) {
        String sql = "SELECT id, type, username, display_name, content, created_at FROM messages ORDER BY id DESC LIMIT ?";
        List<Message> reversed = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    reversed.add(new Message(result.getLong("id"), result.getString("type"),
                            result.getString("username"), result.getString("display_name"),
                            result.getString("content"), result.getLong("created_at")));
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
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO messages(type, username, display_name, content, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, type);
                insert.setString(2, username);
                insert.setString(3, displayName);
                insert.setString(4, content);
                insert.setLong(5, createdAt);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("消息未生成 id");
                    }
                    long id = keys.getLong(1);
                    trimMessages(connection, maxHistory);
                    connection.commit();
                    return new Message(id, type, username, displayName, content, createdAt);
                }
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法保存消息", e);
        }
    }

    private void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        username TEXT PRIMARY KEY COLLATE NOCASE,
                        display_name TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT (unixepoch() * 1000)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL CHECK(type IN ('chat', 'system')),
                        username TEXT,
                        display_name TEXT,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("无法初始化 SQLite 数据库", e);
        }
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
