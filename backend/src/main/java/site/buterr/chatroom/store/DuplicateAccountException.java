package site.buterr.chatroom.store;

/** Thrown when a username already has a registered account. */
public class DuplicateAccountException extends RuntimeException {

    private final String username;

    public DuplicateAccountException(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
