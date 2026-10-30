package site.buterr.finalchat.model;

/**
 * 聊天室消息。与 PR01 的 Message 板上钉钉的不可变风格一脉相承，
 * 但 Final 里内容以原始文本存储，转义交给前端 Vue 插值处理（PR01 的经验：输出端转义才安全）。
 *
 * @param type      {@link #TYPE_CHAT} 用户发言，或 {@link #TYPE_SYSTEM} 系统提示
 * @param username  发言者用户名，系统消息为 null
 */
public record Message(long id, String type, String username, String content, long createdAt) {

    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_SYSTEM = "system";
}
