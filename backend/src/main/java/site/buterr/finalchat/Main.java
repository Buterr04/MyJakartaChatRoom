package site.buterr.finalchat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.websocket.server.WsSci;

import site.buterr.finalchat.chat.ChatEndpoint;
import site.buterr.finalchat.filter.AuthFilter;
import site.buterr.finalchat.listener.SessionLifecycleListener;
import site.buterr.finalchat.servlet.LoginServlet;
import site.buterr.finalchat.servlet.LogoutServlet;
import site.buterr.finalchat.servlet.MeServlet;
import site.buterr.finalchat.servlet.MessagesServlet;
import site.buterr.finalchat.servlet.UsersServlet;
import site.buterr.finalchat.store.ChatRoom;
import site.buterr.finalchat.store.ChatRoomHolder;

/**
 * Final 聊天室启动入口。
 *
 * <p>与前六个 PR 依赖外部安装的 Tomcat 部署 WAR 不同，Final 直接内嵌 Tomcat 10.1，
 * {@code mvn compile exec:java} 一条命令即可运行；仍然是纯 jakarta.servlet /
 * jakarta.websocket 编程模型，servlet、filter、listener、endpoint 一个不少。</p>
 *
 * <p>可选环境变量：{@code FINAL_PORT}（默认 8080）、
 * {@code FINAL_STATIC_DIR}（前端构建产物目录，默认自动探测 ../frontend/dist）。</p>
 */
public final class Main {

    private static final int DEFAULT_PORT = 8080;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("FINAL_PORT", String.valueOf(DEFAULT_PORT)));
        ChatRoom chatRoom = new ChatRoom(ChatRoom.MAX_HISTORY);
        ChatRoomHolder.set(chatRoom);

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("finalchat-tomcat").toString());
        tomcat.setPort(port);
        tomcat.getConnector();

        // docBase 指向前端构建产物；开发模式下前端走 Vite dev server，这里是纯 API 模式
        Context ctx = tomcat.addContext("", resolveStaticDir().toAbsolutePath().toString());
        ctx.setSessionTimeout(60); // 与 PR03~PR06 保持一致：60 分钟

        // 会话销毁（登出/超时/关浏览器）时把用户请出聊天室，对应 PR04~PR06 的 SessionLifecycleListener；
        // 与 web.xml 的 <listener> 等价：按类名注册，监听器以无参构造实例化。
        // 内嵌模式下 webapp 类加载器不含应用类路径，需要显式把父加载器指向应用类加载器
        StandardContext standardCtx = (StandardContext) ctx;
        standardCtx.setParentClassLoader(Main.class.getClassLoader());
        standardCtx.addApplicationListener(SessionLifecycleListener.class.getName());

        // 注册 DefaultServlet：内嵌模式没有 conf/web.xml，静态资源（前端构建产物）和
        // WebSocket 升级请求都需要它兜底——所有路径映射到 servlet 后，WsFilter 才能拦截升级
        Wrapper defaultServlet = Tomcat.addServlet(standardCtx, "default", new DefaultServlet());
        defaultServlet.addMapping("/");
        standardCtx.addWelcomeFile("index.html");

        // 内嵌模式同样没有 conf/web.xml 的 MIME 映射表：不带 Content-Type 的 JS
        // 会被浏览器拒绝当作 ES module 执行，这里手工补上常用类型
        standardCtx.addMimeMapping("js", "application/javascript");
        standardCtx.addMimeMapping("css", "text/css");
        standardCtx.addMimeMapping("html", "text/html");
        standardCtx.addMimeMapping("htm", "text/html");
        standardCtx.addMimeMapping("json", "application/json");
        standardCtx.addMimeMapping("map", "application/json");
        standardCtx.addMimeMapping("svg", "image/svg+xml");
        standardCtx.addMimeMapping("png", "image/png");
        standardCtx.addMimeMapping("ico", "image/x-icon");
        standardCtx.addMimeMapping("woff2", "font/woff2");

        // 显式把 WebSocket 端点交给 WsSci 注册，内嵌模式下不依赖类路径扫描
        ctx.addServletContainerInitializer(new WsSci(), Set.<Class<?>>of(ChatEndpoint.class));

        // 登录态过滤器：/api/* 除 /api/login 外都要求已登录，对应 PR04 的 AuthFilter
        FilterDef authDef = new FilterDef();
        authDef.setFilterName("authFilter");
        authDef.setFilter(new AuthFilter());
        ctx.addFilterDef(authDef);
        FilterMap authMap = new FilterMap();
        authMap.setFilterName("authFilter");
        authMap.addURLPattern("/api/*");
        ctx.addFilterMap(authMap);

        Tomcat.addServlet(ctx, "loginServlet", new LoginServlet(chatRoom)).addMapping("/api/login");
        Tomcat.addServlet(ctx, "meServlet", new MeServlet()).addMapping("/api/me");
        Tomcat.addServlet(ctx, "logoutServlet", new LogoutServlet()).addMapping("/api/logout");
        Tomcat.addServlet(ctx, "usersServlet", new UsersServlet(chatRoom)).addMapping("/api/users");
        Tomcat.addServlet(ctx, "messagesServlet", new MessagesServlet(chatRoom)).addMapping("/api/messages");

        tomcat.start();
        System.out.println("Final 聊天室已启动: http://localhost:" + port
                + "  (静态资源目录: " + ctx.getDocBase() + ")");
        tomcat.getServer().await();
    }

    /**
     * 前端构建产物目录的探测顺序：环境变量 → ../frontend/dist（从 backend 目录启动）→
     * frontend/dist（从仓库根目录启动）→ 临时空目录（纯 API 模式）。
     */
    private static Path resolveStaticDir() throws IOException {
        String configured = System.getenv("FINAL_STATIC_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path fromBackend = Path.of("../frontend/dist");
        if (Files.isDirectory(fromBackend)) {
            return fromBackend;
        }
        Path fromRoot = Path.of("frontend/dist");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        return Files.createTempDirectory("finalchat-empty");
    }
}
