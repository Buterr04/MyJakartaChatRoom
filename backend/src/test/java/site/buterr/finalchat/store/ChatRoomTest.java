package site.buterr.finalchat.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import site.buterr.finalchat.model.Message;
import site.buterr.finalchat.model.User;

class ChatRoomTest {

    @Test
    @DisplayName("登录后用户在线，并产生一条加入的系统消息")
    void loginAddsUserAndSystemMessage() {
        ChatRoom room = new ChatRoom(200);
        User user = room.login("小明");

        assertEquals("小明", user.username());
        assertTrue(room.onlineCount() == 1);
        List<Message> history = room.historyAfter(0);
        assertEquals(1, history.size());
        assertEquals(Message.TYPE_SYSTEM, history.get(0).type());
        assertTrue(history.get(0).content().contains("小明"));
    }

    @Test
    @DisplayName("重名（忽略大小写）登录被拒绝")
    void duplicateUsernameRejected() {
        ChatRoom room = new ChatRoom(200);
        room.login("Alice");

        assertThrows(DuplicateUsernameException.class, () -> room.login("Alice"));
        assertThrows(DuplicateUsernameException.class, () -> room.login("ALICE"));
    }

    @Test
    @DisplayName("敏感词被替换为 **")
    void sensitiveWordsMasked() {
        ChatRoom room = new ChatRoom(200);
        room.login("小明");

        Message sent = room.sendChat("小明", "你去死吧，笨蛋！");

        assertEquals("你**吧，**！", sent.content());
    }

    @Test
    @DisplayName("历史消息超过上限后丢弃最老的")
    void historyTrimmed() {
        ChatRoom room = new ChatRoom(3);
        room.login("小明");
        for (int i = 1; i <= 5; i++) {
            room.sendChat("小明", "消息" + i);
        }

        List<Message> history = room.historyAfter(0);
        assertEquals(3, history.size());
        assertEquals("消息3", history.get(0).content());
        assertEquals("消息4", history.get(1).content());
        assertEquals("消息5", history.get(2).content());
    }

    @Test
    @DisplayName("leave 后用户离线并广播离场系统消息")
    void leaveRemovesUser() {
        ChatRoom room = new ChatRoom(200);
        room.login("小明");

        room.leave("小明", "session-1");

        assertEquals(0, room.onlineCount());
        List<Message> history = room.historyAfter(0);
        assertFalse(history.isEmpty());
        assertTrue(history.get(history.size() - 1).content().contains("离开了聊天室"));
    }

    @Test
    @DisplayName("historyAfter 按消息 id 做增量过滤")
    void historyAfterFiltersById() {
        ChatRoom room = new ChatRoom(200);
        room.login("小明");
        Message first = room.sendChat("小明", "第一条");
        room.sendChat("小明", "第二条");

        List<Message> increment = room.historyAfter(first.id());

        assertEquals(1, increment.size());
        assertEquals("第二条", increment.get(0).content());
    }
}
