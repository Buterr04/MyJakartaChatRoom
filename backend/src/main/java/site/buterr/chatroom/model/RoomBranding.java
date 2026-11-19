package site.buterr.chatroom.model;

/** Chat-room name and icon, stored in local JSON. */
public record RoomBranding(String name, String icon, long updatedAt) {

    public static final String DEFAULT_NAME = "聊天室";
    public static final String DEFAULT_ICON = "💬";

    public static RoomBranding defaults() {
        return new RoomBranding(DEFAULT_NAME, DEFAULT_ICON, 0);
    }
}
