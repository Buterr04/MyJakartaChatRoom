package site.buterr.chatroom.model;

/**
 * 当前在线用户。用户名用于账户与会话识别，显示名称用于界面与消息展示。
 */
public record User(String username, String displayName, Role role, long mutedUntil, long loginAt, String avatar) {

    public User(String username, String displayName, long loginAt) {
        this(username, displayName, Role.USER, 0, loginAt, null);
    }

    public User(String username, String displayName, Role role, long mutedUntil, long loginAt) {
        this(username, displayName, role, mutedUntil, loginAt, null);
    }
}
