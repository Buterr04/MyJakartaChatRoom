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

import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
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

        Message variants = room.sendChat("小明", "FUCK ＦＵＣＫ 傻B 智障");
        assertEquals("** ** ** **", variants.content());

        Message obfuscated = room.sendChat("小明", "F U C K 傻 逼");
        assertEquals("** **", obfuscated.content());
    }

    @Test
    @DisplayName("管理员新增/删除敏感词立即生效")
    void managedSensitiveWordsTakeEffect() {
        ChatRepository repository = new ChatRepository(temporaryDirectory.resolve("managed-words.db"));
        LocalConfigStore configStore = new LocalConfigStore(temporaryDirectory.resolve("managed-config"));
        ChatRoom room = new ChatRoom(200, repository, null, configStore);
        room.login("小明");
        connect(room, room.login("小明"));

        assertTrue(room.addSensitiveWord("广告词"));
        Message filtered = room.sendChat("小明", "这是广告词");
        assertEquals("这是**", filtered.content());

        assertTrue(room.removeSensitiveWord("广告词"));
        Message unfiltered = room.sendChat("小明", "这是广告词");
        assertEquals("这是广告词", unfiltered.content());
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

    @Test
    @DisplayName("显式登出立即离线，不等 30 秒宽限期")
    void explicitLogoutRemovesUserImmediately() throws Exception {
        ChatRoom room = new ChatRoom(200, 60);
        User user = room.login("小明");
        room.bindWs(user, "session-1", webSocket());
        assertEquals(1, room.onlineCount());

        room.leave("小明", "session-1");

        assertEquals(0, room.onlineCount());
        assertTrue(room.webSocketRejection(user, "session-2").isEmpty());
        assertTrue(room.historyAfter(0).stream()
                .anyMatch(message -> message.content().contains("离开了聊天室")));

        // An old grace timer must not remove a session that logged in again.
        room.bindWs(room.login("小明"), "session-2", webSocket());
        Thread.sleep(140);
        assertEquals(1, room.onlineCount());
    }

    @Test
    @DisplayName("强制登录会挤掉旧会话，未强制时拒绝")
    void forceLoginTakesOverPreviousSession() {
        ChatRoom room = new ChatRoom(200);
        Account account = new Account("alice", "Alice", Role.USER, 0);
        User first = room.login(account, "session-1", false);
        room.bindWs(first, "session-1", webSocket());
        assertEquals(1, room.onlineCount());

        assertThrows(AlreadyOnlineException.class, () -> room.login(account, "session-2", false));
        assertEquals(1, room.onlineCount());
        assertTrue(room.isSessionOwner("alice", "session-1"));

        User second = room.login(account, "session-2", true);

        assertEquals(0, room.onlineCount());
        assertTrue(room.isSessionOwner("alice", "session-2"));
        assertFalse(room.isSessionOwner("alice", "session-1"));
        assertTrue(room.isSessionRevoked("session-1"));
        assertTrue(room.webSocketRejection(first, "session-1").isPresent());

        room.bindWs(second, "session-2", webSocket());
        assertEquals(1, room.onlineCount());

        // Even after the new owner logs out, the kicked session stays revoked...
        room.leave("alice", "session-2");
        assertFalse(room.canUseSession("alice", "session-1"));

        // ...until that same session authenticates again with the password (force login).
        User relogin = room.login(account, "session-1", true);
        assertTrue(room.canUseSession("alice", "session-1"));
        room.bindWs(relogin, "session-1", webSocket());
        assertEquals(1, room.onlineCount());
    }

    @Test
    @DisplayName("同一会话切换账号会踢掉旧账号")
    void sameSessionAccountSwitchKicksPreviousUser() {
        ChatRoom room = new ChatRoom(200);
        User alice = room.login(new Account("alice", "Alice", Role.USER, 0), "session-1", false);
        room.bindWs(alice, "session-1", webSocket());
        assertEquals(1, room.onlineCount());

        User bob = room.login(new Account("bob", "Bob", Role.USER, 0), "session-1", false);

        assertEquals(0, room.onlineCount());
        assertFalse(room.isSessionOwner("alice", "session-1"));
        assertTrue(room.isSessionOwner("bob", "session-1"));

        room.bindWs(bob, "session-1", webSocket());
        assertEquals(1, room.onlineCount());
    }

    @Test
    @DisplayName("内存裁剪不影响数据库永久保留，旧消息仍可删除")
    void oldMessageOutsideMemoryCanStillBeDeleted() {
        Path database = temporaryDirectory.resolve("permanent.db");
        ChatRepository repository = new ChatRepository(database);
        ChatRoom room = new ChatRoom(2, repository);
        User user = room.login("alice");
        room.bindWs(user, "session-1", webSocket());
        Message first = room.sendChat("alice", "one");
        room.sendChat("alice", "two");
        room.sendChat("alice", "three");

        assertEquals(4, repository.loadRecentMessages(10).size());
        assertTrue(room.deleteMessage(first.id()));
        assertTrue(repository.findMessage(first.id()).isEmpty());
        assertEquals(3, repository.loadRecentMessages(10).size());
    }

    @Test
    @DisplayName("慢客户端落后超过 ring 容量会被 1013 断开")
    void slowClientOverflowClosesConnection() throws Exception {
        ChatRoom room = new ChatRoom(200);
        SlowSocket slow = new SlowSocket();
        User user = room.login("alice");
        room.bindWs(user, "session-1", slow.session);

        for (int i = 0; i < 130; i++) {
            room.sendChat("alice", "m" + i);
        }
        Thread.sleep(300);
        slow.release();
        Thread.sleep(300);

        assertTrue(slow.closeReason.get() != null);
        assertEquals(1013, slow.closeReason.get().getCloseCode().getCode());
    }

    private static final class SlowSocket {
        final java.util.concurrent.atomic.AtomicReference<CloseReason> closeReason =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Session session;
        volatile SendHandler pending;
        volatile boolean open = true;
        volatile boolean autoComplete;

        SlowSocket() {
            session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
                    new Class<?>[]{Session.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == arguments[0];
                            case "toString":
                                return "SlowSocket";
                            case "getId":
                                return "slow";
                            case "isOpen":
                                return open;
                            case "getAsyncRemote":
                                return asyncRemote();
                            case "close":
                                open = false;
                                if (arguments != null && arguments.length > 0) {
                                    closeReason.set((CloseReason) arguments[0]);
                                }
                                return null;
                            default:
                                if (method.getReturnType() == boolean.class) {
                                    return false;
                                }
                                return null;
                        }
                    });
        }

        private Object asyncRemote() {
            return Proxy.newProxyInstance(RemoteEndpoint.Async.class.getClassLoader(),
                    new Class<?>[]{RemoteEndpoint.Async.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("sendText") && arguments != null && arguments.length == 2) {
                            SendHandler handler = (SendHandler) arguments[1];
                            if (autoComplete) {
                                handler.onResult(new SendResult());
                            } else {
                                pending = handler;
                            }
                            return null;
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == arguments[0];
                        }
                        if (method.getName().equals("toString")) {
                            return "SlowAsyncRemote";
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        return null;
                    });
        }

        void release() {
            autoComplete = true;
            SendHandler handler = pending;
            pending = null;
            if (handler != null) {
                handler.onResult(new SendResult());
            }
        }
    }

    @Test
    @DisplayName("重连时从数据库刷新禁言状态")
    void reconnectRefreshesMutedStateFromRepository() throws Exception {
        Path database = temporaryDirectory.resolve("refresh.db");
        ChatRepository repository = new ChatRepository(database);
        repository.register("alice", "Alice", "password123");
        Account account = repository.findAccount("alice").orElseThrow();
        ChatRoom room = new ChatRoom(200, repository, 40);
        User stale = room.login(account);

        Session firstSocket = webSocket();
        room.bindWs(stale, "session-1", firstSocket);
        room.unbindWs(firstSocket);
        // Grace 40ms: wait until the stale session user is no longer in onlineUsers.
        Thread.sleep(100);
        assertEquals(0, room.onlineCount());

        long mutedUntil = System.currentTimeMillis() + 60_000;
        room.setMutedUntil("alice", mutedUntil).orElseThrow();

        room.bindWs(stale, "session-1", webSocket());

        assertEquals(mutedUntil, room.onlineUsers().get(0).mutedUntil());
        assertThrows(AccountMutedException.class, () -> room.sendChat("alice", "blocked"));
    }
}
