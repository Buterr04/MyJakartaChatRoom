package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.model.RoomBranding;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

/** Both ADMIN and super admin can change the room name/icon. */
public class RoomSettingsServlet extends HttpServlet {

    private static final int MAX_ICON_DATA_URL_LENGTH = 1_500_000;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomBody(String name, String icon) {
    }

    private final ChatRoom chatRoom;

    public RoomSettingsServlet(ChatRoom chatRoom) {
        this.chatRoom = chatRoom;
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        RoomBody body = JsonUtil.read(req, RoomBody.class);
        String name = body == null || body.name() == null ? "" : body.name().trim();
        String icon = body == null || body.icon() == null ? "" : body.icon().trim();
        if (name.isEmpty()) {
            name = RoomBranding.DEFAULT_NAME;
        }
        if (name.length() > 30) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "聊天室名称不能超过 30 个字符"));
            return;
        }
        if (icon.isEmpty()) {
            icon = RoomBranding.DEFAULT_ICON;
        }
        if (icon.startsWith("data:image/")) {
            if (!icon.matches("^data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$")
                    || icon.length() > MAX_ICON_DATA_URL_LENGTH) {
                JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                        Map.of("error", "图标图片不合法或超过 1MB"));
                return;
            }
        } else if (icon.codePointCount(0, icon.length()) > 8) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "图标文字不能超过 8 个字符"));
            return;
        }
        RoomBranding branding = chatRoom.updateRoomBranding(name, icon);
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("room", branding));
    }
}
