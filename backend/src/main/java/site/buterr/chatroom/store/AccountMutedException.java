package site.buterr.chatroom.store;

public final class AccountMutedException extends RuntimeException {

    private final long mutedUntil;

    public AccountMutedException(long mutedUntil) {
        super("账户已被禁言");
        this.mutedUntil = mutedUntil;
    }

    public long getMutedUntil() {
        return mutedUntil;
    }
}
