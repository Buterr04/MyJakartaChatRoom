package site.buterr.finalchat.model;

/**
 * 在线用户。Jackson 原生支持 record，直接作为 REST API 的返回体。
 */
public record User(String username, long loginAt) {
}
