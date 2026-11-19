package site.buterr.chatroom.servlet;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import site.buterr.chatroom.model.Account;
import site.buterr.chatroom.model.Role;
import site.buterr.chatroom.store.ChatRepository;
import site.buterr.chatroom.store.ChatRoom;
import site.buterr.chatroom.util.JsonUtil;

public class AdminUsersServlet extends HttpServlet {

    private static final int MAX_MUTE_MINUTES = 10_080;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MuteBody(Integer minutes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleBody(String role) {
    }

    private final ChatRoom chatRoom;
    private final ChatRepository repository;

    public AdminUsersServlet(ChatRoom chatRoom, ChatRepository repository) {
        this.chatRoom = chatRoom;
        this.repository = repository;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Account actor = currentAccount(req);
        if (actor == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "需要管理员权限"));
            return;
        }
        String self = actor.username();
        List<Account> visible = repository.listAccounts().stream()
                .filter(account -> !account.username().equalsIgnoreCase("Admin"))
                .filter(account -> !account.username().equalsIgnoreCase(self))
                .toList();
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("users", visible));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Account actor = currentAccount(req);
        if (actor == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "需要管理员权限"));
            return;
        }
        String pathInfo = req.getPathInfo();
        if (pathInfo != null && pathInfo.matches("/[^/]+/role")) {
            if (!isSuperAdmin(actor)) {
                JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "仅超级管理员可设置管理员"));
                return;
            }
            changeRole(req, resp, pathInfo.substring(1, pathInfo.length() - "/role".length()));
            return;
        }
        if (pathInfo == null || !pathInfo.matches("/[^/]+/mute")) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "请求路径不合法"));
            return;
        }
        String username = pathInfo.substring(1, pathInfo.length() - "/mute".length());
        Account target = repository.findAccount(username).orElse(null);
        if (target == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "账户不存在"));
            return;
        }
        if (target.role() != Role.USER && !isSuperAdmin(actor)) {
            JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "聊天室管理员只能禁言普通用户"));
            return;
        }
        if ("Admin".equalsIgnoreCase(target.username())) {
            JsonUtil.write(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "不能禁言超级管理员"));
            return;
        }
        if (target.username().equalsIgnoreCase(actor.username())) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "不能禁言自己"));
            return;
        }
        MuteBody body = JsonUtil.read(req, MuteBody.class);
        if (body == null || body.minutes() == null
                || body.minutes() < 0 || body.minutes() > MAX_MUTE_MINUTES) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "禁言时长需为 0~" + MAX_MUTE_MINUTES + " 分钟，0 表示解除禁言"));
            return;
        }
        long mutedUntil = body.minutes() == 0 ? 0 : System.currentTimeMillis() + body.minutes() * 60_000L;
        Optional<Account> account = chatRoom.setMutedUntil(username, mutedUntil);
        if (account.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "账户不存在"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("account", account.get()));
    }

    private Account currentAccount(HttpServletRequest req) {
        Object username = req.getSession(false) == null ? null : req.getSession(false).getAttribute("username");
        return username == null ? null : repository.findAccount(String.valueOf(username)).orElse(null);
    }

    private static boolean isSuperAdmin(Account account) {
        return account != null && account.role() == Role.ADMIN
                && "Admin".equalsIgnoreCase(account.username());
    }

    private void changeRole(HttpServletRequest req, HttpServletResponse resp, String username) throws IOException {
        RoleBody body = JsonUtil.read(req, RoleBody.class);
        Role role;
        try {
            role = body == null || body.role() == null ? null : Role.valueOf(body.role());
        } catch (IllegalArgumentException e) {
            role = null;
        }
        if (role == null) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "角色只能为 USER 或 ADMIN"));
            return;
        }
        String currentUsername = String.valueOf(req.getSession(false).getAttribute("username"));
        if (role == Role.USER && (username.equalsIgnoreCase("Admin") || username.equalsIgnoreCase(currentUsername))) {
            JsonUtil.write(resp, HttpServletResponse.SC_BAD_REQUEST,
                    Map.of("error", "不能取消默认管理员或当前登录账户的管理员权限"));
            return;
        }
        Optional<Account> account = chatRoom.setRole(username, role);
        if (account.isEmpty()) {
            JsonUtil.write(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "账户不存在"));
            return;
        }
        JsonUtil.write(resp, HttpServletResponse.SC_OK, Map.of("account", account.get()));
    }
}
