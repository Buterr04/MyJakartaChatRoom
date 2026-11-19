package site.buterr.chatroom.store;

/** Raised when an account is already logged in from another HTTP session and force-login was not requested. */
public class AlreadyOnlineException extends RuntimeException {

    public AlreadyOnlineException(String username) {
        super("该账户已在其他设备登录: " + username);
    }
}
