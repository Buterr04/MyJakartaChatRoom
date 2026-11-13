package site.buterr.finalchat.model;

/**
 * 聊天室消息。username 是稳定的账户标识，displayName 是发送时的展示名；
 * 系统消息的两项均为 null。消息内容保持原始文本，由前端转义输出。
 */
public record Message(long id, String type, String username, String displayName, String content, long createdAt) {

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_SYSTEM = "system";

    /** Compatible constructor for the original in-memory domain model. */
    public Message(long id, String type, String username, String content, long createdAt) {
        this(id, type, username, username, content, createdAt);
    }
}
