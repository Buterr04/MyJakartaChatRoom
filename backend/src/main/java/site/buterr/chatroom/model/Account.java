package site.buterr.chatroom.model;

/**
 * 已注册账户中可安全返回给应用层的公开资料。
 */
public record Account(String username, String displayName, Role role, long mutedUntil, String avatar) {

    public Account(String username, String displayName) {
        this(username, displayName, Role.USER, 0, null);
    }

    public Account(String username, String displayName, Role role, long mutedUntil) {
        this(username, displayName, role, mutedUntil, null);
    }
}
