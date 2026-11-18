package site.buterr.chatroom.store;

public class UserOfflineException extends RuntimeException {

    public UserOfflineException(String message) {
        super(message);
    }
}
