package site.buterr.finalchat.model;

/**
 * 当前在线用户。用户名用于账户与会话识别，显示名称用于界面与消息展示。
 */
public record User(String username, String displayName, long loginAt) {
}
