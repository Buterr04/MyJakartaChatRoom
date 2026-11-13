package site.buterr.chatroom.model;

/**
 * 已注册账户中可安全返回给应用层的公开资料。
 */
public record Account(String username, String displayName) {
}
