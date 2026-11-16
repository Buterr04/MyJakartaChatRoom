package site.buterr.chatroom.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.Message;
import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.model.User;

class ChatRoomTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @TempDir
    Path temporaryDirectory;

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

    @Test
    @DisplayName("禁言账户可以阅读和解除禁言后继续发言")
    void mutedAccountCannotSendUntilUnmuted() {
        ChatRoom room = new ChatRoom(200);
        room.login(new Account("alice", "Alice", Role.USER, 0));
        int historySize = room.historyAfter(0).size();
        long mutedUntil = System.currentTimeMillis() + 60_000;

        room.setMutedUntil("alice", mutedUntil).orElseThrow();
        assertEquals(mutedUntil, room.onlineUsers().get(0).mutedUntil());
        assertThrows(AccountMutedException.class, () -> room.sendChat("alice", "blocked"));
        assertEquals(historySize, room.historyAfter(0).size());

        room.setMutedUntil("alice", 0).orElseThrow();
        assertEquals("allowed", room.sendChat("alice", "allowed").content());
    }

    @Test
    @DisplayName("管理员删除聊天消息但保留系统消息")
    void deleteRemovesOnlyChatMessages() {
        ChatRoom room = new ChatRoom(200);
        room.login("alice");
        Message system = room.historyAfter(0).get(0);
        Message chat = room.sendChat("alice", "delete me");

        assertFalse(room.deleteMessage(system.id()));
        assertTrue(room.deleteMessage(chat.id()));
        assertFalse(room.deleteMessage(chat.id()));
        assertTrue(room.historyAfter(0).stream().noneMatch(message -> message.id() == chat.id()));
    }

    @Test
    @DisplayName("角色和头像更新会同步到在线用户")
    void roleAndAvatarUpdatesReachOnlineUser() {
        ChatRoom room = new ChatRoom(200);
        room.login(new Account("alice", "Alice", Role.USER, 0));

        Account promoted = room.setRole("alice", Role.ADMIN).orElseThrow();
        assertEquals(Role.ADMIN, promoted.role());
        assertEquals(Role.ADMIN, room.onlineUsers().get(0).role());

        Account updated = room.setAvatar("alice", "data:image/png;base64,avatar").orElseThrow();
        assertEquals("data:image/png;base64,avatar", updated.avatar());
        assertEquals("data:image/png;base64,avatar", room.onlineUsers().get(0).avatar());
        assertEquals(Role.ADMIN, room.onlineUsers().get(0).role());
    }

    @Test
    @DisplayName("图片消息跨重启恢复，删除时清理元数据和文件")
    void imageMessageIsRestoredAndDeletedWithItsFile() throws Exception {
        Path database = temporaryDirectory.resolve("chat.db");
        ImageStorage storage = new ImageStorage(temporaryDirectory.resolve("uploads"));
        ChatRepository repository = new ChatRepository(database);
        ChatRoom room = new ChatRoom(200, repository, storage);
        room.login("alice");

        Message sent = room.sendImage("alice", "../pixel.png", PNG);
        assertEquals("pixel.png", sent.image().originalName());
        assertEquals(PNG.length, storage.read(sent.image()).length);

        ChatRoom restored = new ChatRoom(200, new ChatRepository(database), storage);
        Message restoredMessage = restored.historyAfter(0).stream()
                .filter(message -> message.id() == sent.id())
                .findFirst()
                .orElseThrow();
        assertEquals(sent.image(), restoredMessage.image());

        assertTrue(restored.deleteMessage(sent.id()));
        assertTrue(repository.findImage(sent.image().id()).isEmpty());
        assertThrows(NoSuchFileException.class, () -> storage.read(sent.image()));
    }
}
