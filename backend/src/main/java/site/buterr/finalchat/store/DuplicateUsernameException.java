package site.buterr.finalchat.store;

/**
 * 用户名重复（已在线）时抛出，由 LoginServlet 翻译成 409 响应。
 */
public class DuplicateUsernameException extends RuntimeException {

    private final String username;

    public DuplicateUsernameException(String username) {
        super("用户名已在线: " + username);
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
