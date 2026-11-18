package site.buterr.chatroom.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.lang.reflect.Proxy;

import jakarta.websocket.Session;

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

    private static void connect(ChatRoom room, User user) {
        room.bindWs(user, "session-" + user.username(), webSocket());
    }

    private static Session webSocket() {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == arguments[0];
                    }
                    if (method.getName().equals("toString")) {
                        return "WebSocket@" + System.identityHashCode(proxy);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("WebSocket 连接后用户在线并产生加入消息")
    void webSocketConnectionAddsOnlineUserAndSystemMessage() {
        ChatRoom room = new ChatRoom(200);
        User user = room.login("小明");
        assertEquals(0, room.onlineCount());
        assertThrows(UserOfflineException.class, () -> room.sendChat("小明", "offline"));

        connect(room, user);
        assertEquals("小明", user.username());
        assertTrue(room.onlineCount() == 1);
        List<Message> history = room.historyAfter(0);
        assertEquals(1, history.size());
        assertEquals(Message.TYPE_SYSTEM, history.get(0).type());
        assertTrue(history.get(0).content().contains("小明"));
    }

    @Test
    @DisplayName("同一账户忽略大小写只允许一个 WebSocket 连接会话在线")
    void duplicateUsernameRejected() {
        ChatRoom room = new ChatRoom(200);
        connect(room, room.login("Alice"));
        assertThrows(DuplicateUsernameException.class,
                () -> room.bindWs(room.login("ALICE"), "session-ALICE", webSocket()));
        assertEquals(1, room.onlineCount());
    }

    @Test
    @DisplayName("同一 HTTP 会话的多个标签页只算一个在线用户")
    void multipleTabsShareOneOnlineUser() throws Exception {
        ChatRoom room = new ChatRoom(200, 40);
        User user = room.login("小明");
        Session firstTab = webSocket();
        Session secondTab = webSocket();

        room.bindWs(user, "session-1", firstTab);
        room.bindWs(user, "session-1", secondTab);
        assertEquals(1, room.onlineCount());

        room.unbindWs(firstTab);
        assertEquals(1, room.onlineCount());

        room.unbindWs(secondTab);
        Thread.sleep(120);
        assertEquals(0, room.onlineCount());
    }

    @Test
    @DisplayName("敏感词被替换为 **")
    void sensitiveWordsMasked() {
        ChatRoom room = new ChatRoom(200);
        room.login("小明");
        connect(room, room.login("小明"));

        Message sent = room.sendChat("小明", "你去死吧，笨蛋！");

        assertEquals("你**吧，**！", sent.content());
    }

    @Test
    @DisplayName("历史消息超过上限后丢弃最老的")
    void historyTrimmed() {
        ChatRoom room = new ChatRoom(3);
        room.login("小明");
        connect(room, room.login("小明"));
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
    @DisplayName("最后一个 WebSocket 断开满宽限期后用户才离线")
    void disconnectingLastWebSocketRemovesUserAfterGracePeriod() throws Exception {
        ChatRoom room = new ChatRoom(200, 40);
        User user = room.login("小明");
        Session socket = webSocket();
        room.bindWs(user, "session-1", socket);
        assertEquals(1, room.onlineCount());

        room.unbindWs(socket);

        assertEquals(1, room.onlineCount());
        Thread.sleep(120);
        assertEquals(0, room.onlineCount());
        List<Message> history = room.historyAfter(0);
        assertFalse(history.isEmpty());
        assertTrue(history.get(history.size() - 1).content().contains("离开了聊天室"));
    }

    @Test
    @DisplayName("宽限期内重连不产生离开或再次加入消息")
    void reconnectingDuringGracePeriodKeepsUserOnline() throws Exception {
        ChatRoom room = new ChatRoom(200, 120);
        User user = room.login("小明");
        Session firstSocket = webSocket();
        room.bindWs(user, "session-1", firstSocket);
        room.unbindWs(firstSocket);

        Thread.sleep(30);
        room.bindWs(user, "session-1", webSocket());
        Thread.sleep(140);

        assertEquals(1, room.onlineCount());
        assertEquals(1, room.historyAfter(0).size());
        assertTrue(room.historyAfter(0).get(0).content().contains("加入了聊天室"));
    }

    @Test
    @DisplayName("historyAfter 按消息 id 做增量过滤")
    void historyAfterFiltersById() {
        ChatRoom room = new ChatRoom(200);
        room.login("小明");
        connect(room, room.login("小明"));
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
        connect(room, room.login("alice"));
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
        connect(room, room.login("alice"));
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
        connect(room, room.login("alice"));

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
        connect(room, room.login("alice"));

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

    @Test
    @DisplayName("同一账户不能从另一个 HTTP 会话连接")
    void sameAccountCannotBindFromAnotherHttpSession() {
        ChatRoom room = new ChatRoom(200);
        Account account = new Account("alice", "Alice", Role.USER, 0);
        User user = room.login(account);
        room.bindWs(user, "session-1", webSocket());

        assertThrows(DuplicateUsernameException.class,
                () -> room.bindWs(user, "session-2", webSocket()));
        assertTrue(room.webSocketRejection(user, "session-2").isPresent());
        assertTrue(room.webSocketRejection(user, "session-1").isEmpty());
    }
}
