# Final 聊天室 · 开发手册

> 本手册面向要运行、理解、修改或扩展本项目的开发者。项目定位与功能速览见 [README.md](./README.md)，本手册聚焦"怎么跑起来、代码怎么组织、为什么这样写、如何继续开发"。

## 目录

1. [架构总览](#1-架构总览)
2. [环境准备](#2-环境准备)
3. [构建与运行](#3-构建与运行)
4. [后端架构详解](#4-后端架构详解)
5. [REST API 规范](#5-rest-api-规范)
6. [WebSocket 协议规范](#6-websocket-协议规范)
7. [前端架构详解](#7-前端架构详解)
8. [测试指南](#8-测试指南)
9. [内嵌 Tomcat 装配要点（踩坑实录）](#9-内嵌-tomcat-装配要点踩坑实录)
10. [二次开发指南](#10-二次开发指南)
11. [FAQ / 故障排查](#11-faq--故障排查)
12. [附录：默认参数一览](#12-附录默认参数一览)

---

## 1. 架构总览

### 1.1 一图看懂

```
                          ┌────────────────────────────────────────────────┐
                          │              后端（Java 21 单进程）              │
                          │                                                │
┌──────────────┐  HTTP    │  ┌──────────┐   ┌──────────────────────────┐   │
│              │ ───────▶ │  │AuthFilter│──▶│ REST Servlets            │   │
│ Vite + Vue 3 │  /api/*  │  └──────────┘   │ /api/login /me /logout   │   │
│  前端 SPA    │          │                 │ /api/users /api/messages │   │
│              │ ◀─────── │                 └───────────┬──────────────┘   │
│  ChatView    │  JSON    │                             │ 读写              │
│  LoginView   │          │                             ▼                  │
│              │          │                 ┌──────────────────────────┐   │
│              │  WS      │  ┌──────────┐   │  ChatRoom（核心状态）      │   │
│              │ ───────▶ │  │/ws/chat  │──▶│  · onlineUsers 在线用户    │   │
│              │ /ws/chat │  │ Endpoint │◀──│  · history 消息历史(≤200) │   │
│              │  推送    │  └──────────┘   │  · wsByHttpSession 连接表 │   │
│              │ ◀─────── │   WsSci 注册    └───────────┬──────────────┘   │
└──────────────┘  广播    │                             │ 广播              │
                          │                    chat-broadcaster（单线程）   │
                          │                    按连接排队 + 回调链串行发送    │
                          └────────────────────────────────────────────────┘
```

架构一句话概括：**命令走 REST（request/response，状态码明确），事件走 WebSocket（服务端主动推送）**。所有共享状态收拢在 `ChatRoom` 一个对象里，不再像 PR03~PR06 那样散落在 ServletContext 属性中。

### 1.2 技术选型与理由

| 层 | 选型 | 理由 |
| --- | --- | --- |
| 运行时 | Java 21 | 课程最新实验已用 16+，record、文本块等特性降低样板代码 |
| Web 容器 | 内嵌 Tomcat 10.1.42 | 一条 `mvn exec:java` 启动，告别"装 Tomcat → 部署 WAR"；编程模型仍是纯 `jakarta.servlet` / `jakarta.websocket` |
| JSON | Jackson 2.18 | 原生支持 record，无需额外模块 |
| 实时通道 | jakarta.websocket（JSR 356） | 课程体系的自然延伸，浏览器原生支持 |
| 前端构建 | Vite 7 | 秒级冷启动、原生 ESM、代理配置简单 |
| 前端框架 | Vue 3（Composition API） | 插值默认转义（XSS 防护与 PR01 的经验一致），单文件组件足够覆盖本项目规模 |
| 测试 | JUnit 6 | 沿用 PR03 以来的课程选择 |

---

## 2. 环境准备

### 2.1 版本要求

| 工具 | 最低版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | `pom.xml` 里 `maven.compiler.release=21` |
| Maven | 3.9+ | 仅后端；也可用 IDE 内置 Maven |
| Node.js | 20.19+ 或 22.12+ | Vite 7 的硬性要求；构建前端时才需要 |

### 2.2 验证环境

```bash
java -version      # 21.x
mvn -version       # 3.9.x（注意 Maven 顶部显示的 Java 版本也要 ≥ 21）
node -v && npm -v
```

> ⚠️ 如果 `mvn -version` 显示的 Java 版本低于 21（Maven 会用它跑编译），设置 `JAVA_HOME` 指向 JDK 21 后再执行 Maven 命令。

---

## 3. 构建与运行

### 3.1 生产模式（单进程，适合演示）

```bash
cd Final/frontend
npm install          # 首次执行
npm run build        # 产物输出到 frontend/dist

cd ../backend
mvn compile exec:java
# 控制台出现 "Final 聊天室已启动: http://localhost:8080" 即成功
```

启动时 `Main.resolveStaticDir()` 会自动探测 `../frontend/dist`（从 backend 目录启动）或 `frontend/dist`（从仓库根目录启动）作为静态资源目录。打开 <http://localhost:8080> 即可。

### 3.2 开发模式（前后端热更新，适合开发）

```bash
# 终端 1：后端（dist 尚不存在时自动进入纯 API 模式）
cd Final/backend && mvn compile exec:java

# 终端 2：前端 dev server
cd Final/frontend && npm run dev
# 打开 http://localhost:5173（/api 与 /ws 自动代理到 8080）
```

开发模式下改 Java 代码需要重启后端（`exec:java` 不支持热加载）；改 Vue 组件则是毫秒级热更新。

### 3.3 运行期配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FINAL_PORT` | `8080` | HTTP/WS 监听端口 |
| `FINAL_STATIC_DIR` | 自动探测 | 静态资源目录；显式指定后跳过探测 |

### 3.4 常用命令速查

```bash
mvn test                              # 跑后端单元测试
mvn clean package                     # 完整构建（含测试）
mvn compile exec:java                 # 启动后端
lsof -ti:8080 | xargs kill            # 杀掉占用 8080 的旧进程（ macOS）
cd frontend && npm run build          # 重新构建前端（改了前端代码后）
```

---

## 4. 后端架构详解

### 4.1 包结构与职责

```
site.buterr.finalchat
├── Main                     # 启动入口 + 全部装配（唯一的"胶水"）
├── model
│   ├── User                 # record(username, loginAt)，登录响应 / 在线名单元素
│   └── Message              # record(id, type, username, content, createdAt)
├── store                    # 领域状态层（不依赖 servlet API，可单测）
│   ├── ChatRoom             # 核心：在线用户 + 历史 + 连接登记 + 广播
│   ├── ChatRoomHolder       # 静态引用，供 WebSocket 端点获取 ChatRoom
│   └── DuplicateUsernameException
├── servlet                  # REST 层（薄：校验 + 调 store + 写 JSON）
│   ├── LoginServlet         # POST /api/login
│   ├── MeServlet            # GET  /api/me
│   ├── LogoutServlet        # POST /api/logout
│   ├── UsersServlet         # GET  /api/users
│   └── MessagesServlet      # GET/POST /api/messages
├── filter
│   └── AuthFilter           # /api/* 登录态守卫（放行 /api/login）
├── listener
│   └── SessionLifecycleListener  # 会话销毁 → ChatRoom.leave
├── chat
│   ├── ChatEndpoint         # @ServerEndpoint("/ws/chat")
│   └── ChatConfigurator     # 握手阶段把 HTTP 会话传给端点实例
└── util
    └── JsonUtil             # ObjectMapper 封装（读写 JSON）
```

分层约定：**servlet 只做参数校验和状态码翻译，业务规则全部在 `ChatRoom`**。这保证了 `ChatRoom` 可以脱离 servlet 容器直接单元测试（见 `ChatRoomTest`）。

### 4.2 启动装配流程（Main.java 逐段解析）

`Main.main()` 的执行顺序就是理解本项目的地图：

```java
// 1. 创建核心状态对象，并通过构造器注入各组件
ChatRoom chatRoom = new ChatRoom(ChatRoom.MAX_HISTORY);
ChatRoomHolder.set(chatRoom);              // WebSocket 端点拿不到 ServletContext，走静态引用

// 2. 起内嵌 Tomcat
Tomcat tomcat = new Tomcat();
tomcat.setBaseDir(Files.createTempDirectory(...).toString());  // work 目录
tomcat.setPort(port);
tomcat.getConnector();                     // 10.1 里必须显式取一次才创建连接器

// 3. 根上下文：docBase 指向前端构建产物（探测逻辑见 resolveStaticDir）
Context ctx = tomcat.addContext("", resolveStaticDir().toAbsolutePath().toString());
ctx.setSessionTimeout(60);                 // 与课程实验一致：60 分钟

// 4. 监听器：按类名注册（等价 web.xml 的 <listener>，需要无参构造）
StandardContext standardCtx = (StandardContext) ctx;
standardCtx.setParentClassLoader(Main.class.getClassLoader());  // 踩坑点 ④
standardCtx.addApplicationListener(SessionLifecycleListener.class.getName());

// 5. 注册 DefaultServlet + MIME 映射 + welcome file（踩坑点 ①②）
Wrapper defaultServlet = Tomcat.addServlet(standardCtx, "default", new DefaultServlet());
defaultServlet.addMapping("/");
standardCtx.addWelcomeFile("index.html");
standardCtx.addMimeMapping("js", "application/javascript");
// ... css / html / json / svg / png 等

// 6. WebSocket：显式把端点类交给 WsSci，不依赖类路径扫描
ctx.addServletContainerInitializer(new WsSci(), Set.<Class<?>>of(ChatEndpoint.class));

// 7. 过滤器：FilterDef + FilterMap 等价 web.xml 的 <filter>/<filter-mapping>
FilterDef authDef = new FilterDef();
authDef.setFilterName("authFilter");
authDef.setFilter(new AuthFilter());
ctx.addFilterDef(authDef);
// FilterMap 把 authFilter 挂到 /api/*

// 8. Servlet：实例注册 + 构造器注入 ChatRoom
Tomcat.addServlet(ctx, "loginServlet", new LoginServlet(chatRoom)).addMapping("/api/login");
// ... 其余四个

// 9. 启动并阻塞主线程
tomcat.start();
tomcat.getServer().await();
```

注意两个"注入方式"的差异：**Servlet/Filter 用实例注册（可构造器注入），Listener 只能按类名实例化（经 `ChatRoomHolder` 获取单例）**，这是内嵌 Tomcat 的约束所致（详见第 9 节）。

### 4.3 一次聊天的完整生命周期

**登录 → 发消息 → 其他人收到：**

```
浏览器A(alice)                后端                                   浏览器B(bob)
    │                          │                                       │
    │ POST /api/login          │                                       │
    │─────────────────────────▶│ AuthFilter 放行 /api/login            │
    │                          │ ChatRoom.login("alice")               │
    │                          │   查重 → 放入 onlineUsers              │
    │                          │   publishSystem("alice 加入了聊天室")   │
    │                          │   → 广播 message + online             │
    │◀── 200 {username,...} ───│                    ────────────────────▶│ (B 已连接时)
    │ Set-Cookie: JSESSIONID   │                                       │
    │                          │                                       │
    │ ws://…/ws/chat 握手 ─────▶│ ChatConfigurator.modifyHandshake      │
    │  (自动带 JSESSIONID)      │   从握手请求取出 HttpSession           │
    │                          │ getEndpointInstance → new ChatEndpoint│
    │◀── 101 Switching ────────│ onOpen: 校验 username → bindWs        │
    │◀── {type:"online"} ──────│   （对齐一次在线名单）                  │
    │                          │                                       │
    │ POST /api/messages ──────▶│ sendChat("alice","你好")              │
    │                          │   敏感词过滤 → 入历史 → 广播           │
    │                          │   broadcaster 入队 → 串行发送          │
    │◀── 201 {message} ────────│                                       │
    │◀── {type:"message"} ─────────────────────────────────────────────▶│
    │  （自己的消息也回显，      │                                       │
    │    保证全员顺序一致）      │                                       │
```

**登出 / 会话超时 / 关浏览器（三条路殊途同归）：**

```
POST /api/logout ──▶ session.invalidate() ──┐
会话 60 分钟无操作 ──▶ 容器超时回收 ──────────┤──▶ SessionLifecycleListener.sessionDestroyed
直接关闭浏览器 ──▶ 容器超时回收（≤60min）────┘         │
                                                      ▼
                                          ChatRoom.leave(username, sessionId)
                                            ① 关闭该会话的所有 WS 连接(1001)
                                            ② 从 onlineUsers 移除
                                            ③ 广播"离开了聊天室" + 新在线名单
```

客户端侧：主动登出时前端先置 `leaving=true` 再调接口，避免对 1001 关闭触发重连；会话超时被服务端 1008 拒绝时，前端直接回到登录页（`ChatView.ws.onclose`）。

### 4.4 核心类解析

#### ChatRoom：状态 + 并发模型

```java
private final Map<String, User> onlineUsers = new ConcurrentHashMap<>();       // 用户名 → 用户
private final List<Message> history = new ArrayList<>();                       // 由 synchronized(this) 保护
private final Map<String, Set<Session>> wsByHttpSession = new ConcurrentHashMap<>(); // 会话id → WS连接集合
private final Map<Session, Queue<String>> outbound = new ConcurrentHashMap<>(); // 每连接出站队列
private final Set<Session> draining = ConcurrentHashMap.newKeySet();            // 正在发送链上的连接
private final AtomicLong idGen = new AtomicLong(1);                            // 消息 id 发号器
private final ExecutorService broadcaster = /* 单守护线程 "chat-broadcaster" */;
```

并发设计三条规则：

1. **所有对 `history` 的读写都在 `synchronized(this)` 内**（`login` / `leave` / `sendChat` / `historyAfter`），配合 `idGen` 保证消息 id 单调递增；
2. **所有下行推送都提交给单线程 `broadcaster`**：提交顺序 = 消息 id 顺序 = 每个连接的接收顺序；
3. **连接注册表、出站队列等纯并发容器不加粗锁**，读写各自安全。

#### 广播链路：为什么需要出站队列

Tomcat 的 `RemoteEndpoint.Async` **不允许同一连接上重叠写入**——上一条消息还没写完就调 `sendText` 会抛 `IllegalStateException: TEXT_FULL_WRITING`（本项目的真实 bug，测试发现）。因此：

```java
// 广播线程只入队
private void enqueue(Session ws, String json) {
    if (!ws.isOpen()) return;
    outbound.computeIfAbsent(ws, k -> new ConcurrentLinkedQueue<>()).add(json);
    flush(ws);
}

private void flush(Session ws) {
    if (!draining.add(ws)) return;   // 该连接已有发送链，链尾会取走新消息
    sendNext(ws);
}

private void sendNext(Session ws) {
    ...
    String next = queue.poll();
    if (next == null) { /* 收尾 + 竞态兜底 */ return; }
    ws.getAsyncRemote().sendText(next, result -> sendNext(ws));  // 回调链串行发送
}
```

这样单条消息在任意连接上**严格串行**，慢客户端只会让自己的队列变长，不阻塞其他连接。`flush`/`sendNext` 之间用 `draining` 集合防止出现两条并行发送链，"收尾与入队竞态"用二次检查兜底。

#### ChatConfigurator：握手期拿到 HTTP 会话

浏览器发起 WS 握手时会自动带上 Cookie，因此握手阶段能取到 HttpSession。难点是把它**安全地**传给"这条连接"的端点实例——官方示例直接塞进 `ServerEndpointConfig.getUserProperties()`，但那张表是所有握手共享的，并发握手会串号。Tomcat 对同一次握手保证"先 `modifyHandshake` 再 `getEndpointInstance`，且同线程"，于是：

```java
public class ChatConfigurator extends ServerEndpointConfig.Configurator {
    private HttpSession pending;                       // 配合 synchronized 形成"交接棒"
    @Override public synchronized void modifyHandshake(...) {
        pending = (HttpSession) request.getHttpSession();
    }
    @Override public synchronized <T> T getEndpointInstance(Class<T> endpointClass) {
        HttpSession httpSession = pending; pending = null;
        return (T) new ChatEndpoint(httpSession);      // 每条连接一个新实例，携带自己的会话
    }
}
```

端点拿到会话后：`onOpen` 校验 `username` 属性，没有则以 1008（VIOLATED_POLICY）关闭——这就是"WS 也有鉴权"的实现，也是前端"被踢回登录页"的信号。

#### Servlet 层约定

- 请求体：`JsonUtil.read(request, XxxBody.class)`，body 缺失/非法 JSON 返回 null，由 servlet 翻译成 400；
- 响应：`JsonUtil.write(resp, status, body)`，统一 `application/json; charset=UTF-8`；
- 错误格式统一为 `{"error": "中文提示"}`，前端直接展示；
- 每个业务请求体定义为 servlet 内的私有 `record`（如 `LoginBody`、`SendBody`），`@JsonIgnoreProperties(ignoreUnknown = true)` 容忍多余字段。

#### SessionLifecycleListener

```java
public void sessionDestroyed(HttpSessionEvent se) {
    String username = (String) se.getSession().getAttribute("username");
    chatRoom.leave(username, se.getSession().getId());   // username 可能为 null（从未登录的会话），leave 内部会忽略
}
```

注意：**在线人数不在这里加减**——它永远是 `onlineUsers.size()` 的派生值，从根上避免 PR04~PR06 那种"计数器对不上"的 bug。

### 4.5 线程模型一览

| 线程 | 执行的代码 | 需要注意 |
| --- | --- | --- |
| Tomcat NIO 请求线程 | Filter / Servlet / `ChatRoom` 写操作 | `history` 写操作持 `this` 锁 |
| `chat-broadcaster`（单守护线程） | `toJson` + 出队入队（`enqueue`） | 只入队不发送，保证顺序 |
| Tomcat WS 容器线程 | `@OnOpen/@OnClose/@OnError` | `bindWs` / `unbindWs` 操作并发容器 |
| SendHandler 回调线程 | `sendNext` 逐条发送 | 每连接最多一条发送链（`draining` 保证） |
| 容器会话回收线程 | `sessionDestroyed` → `leave` | 先关 WS 再删用户 |

---

## 5. REST API 规范

所有接口同源（生产模式与后端同端口；开发模式经 Vite 代理）。除 `/api/login` 外都需要有效会话，否则 401。

| 方法 | 路径 | 请求体 | 成功响应 | 失败 |
| --- | --- | --- | --- | --- |
| POST | `/api/login` | `{"username":"alice"}` | `200` `{"username":"alice","loginAt":...}` + Set-Cookie | `400` 空/长度不符；`409` 重名（忽略大小写） |
| GET | `/api/me` | — | `200` `{"username":"alice"}` | `401` |
| POST | `/api/logout` | — | `200` `{"ok":true}`（并触发离场广播） | — |
| GET | `/api/users` | — | `200` `{"count":2,"users":[User...]}`（按登录时间排序） | `401` |
| GET | `/api/messages?after={id}` | — | `200` `{"messages":[Message...]}`；`after>0` 时只返回 id 更大的增量 | `401` |
| POST | `/api/messages` | `{"content":"..."}` | `201` `{"message":Message}` | `400` 空/超 500 字符；`401` |

Message 的 JSON 形态：

```json
{"id":3, "type":"chat", "username":"alice", "content":"你好", "createdAt":1792810797079}
{"id":1, "type":"system", "username":null, "content":"alice 加入了聊天室", "createdAt":...}
```

curl 冒烟示例（`-c/-b` 维持会话）：

```bash
curl -s -c a.jar -H 'Content-Type: application/json' -d '{"username":"alice"}' localhost:8080/api/login
curl -s -b a.jar -H 'Content-Type: application/json' -d '{"content":"你好"}' localhost:8080/api/messages
curl -s -b a.jar 'localhost:8080/api/messages?after=0'
```

## 6. WebSocket 协议规范

- 端点：`ws(s)://<host>/ws/chat`；握手自动携带 Cookie，未登录握手会被立即以关闭码 **1008** 关闭；
- 服务端 → 客户端（两种包）：

```jsonc
{"type":"message","data":{ ...Message... }}                                    // 新消息（chat/system）
{"type":"online","data":{"count":2,"users":[{"username":"alice","loginAt":...}]}}  // 名单变化
```

- 客户端 → 服务端：文本 `"ping"` → 服务端回 `"pong"`（保活探测用）；
- 主动关闭码约定：`1001 GOING_AWAY` = 用户登出/会话销毁被服务端正常关闭；`1008 VIOLATED_POLICY` = 未登录/会话过期，客户端应停止重连并回登录页。

时序约定（前端依赖）：

1. 客户端**先连 WS，再拉 REST 历史**——两条消息之间没有空窗：拉取之后产生的新消息必经 WS 推送到达；
2. `bindWs` 时服务端会先推一帧当前 `online` 快照，客户端以 REST 拉到的名单为准即可（二者最终一致）。

## 7. 前端架构详解

### 7.1 目录结构与职责

```
frontend/
├── index.html               # 唯一 HTML，挂载点 #app
├── vite.config.js           # 插件 + 开发代理
└── src/
    ├── main.js              # createApp(App).mount('#app')
    ├── style.css            # 全部样式（CSS 变量 + BEM-ish 类名，无 UI 库）
    ├── App.vue              # 根组件：会话恢复 + 视图切换
    └── components/
        ├── LoginView.vue    # 登录卡片（props: login 函数）
        └── ChatView.vue     # 聊天主界面 + WS 客户端全部逻辑
```

刻意没有引入 Pinia/Vue Router：状态就两个视图 + 一份消息列表，`App.vue` 持有 `me`，其余状态在 `ChatView` 内部，够用且好讲。

### 7.2 Vite 代理（vite.config.js）

```js
server: {
  port: 5173,
  proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: true },
    '/ws':  { target: 'ws://localhost:8080',   ws: true },   // WebSocket 也要代理！
  },
},
```

前端所有请求都写**同源相对路径**（`fetch('/api/...')`、`new WebSocket('ws(s)://' + location.host + '/ws/chat')`），开发时由代理转发、生产时天然同源——**代码零改动**地适配两种模式。

### 7.3 组件与状态流

```
App.vue
│  me = null | {username}        onMounted: GET /api/me 恢复会话
│  handleLogin(name): POST /api/login，失败 throw Error(data.error)
│  handleLogout():    me = null
│
├── <LoginView :login="handleLogin" />          v-if="!me"
│     内部只管输入校验与 loading，登录失败把 e.message 显示在卡片上
└── <ChatView :me="me" @logout="handleLogout" />  v-else
      内部状态：messages / users / onlineCount / connected / draft / sendError
```

渲染规则：`m.type === 'system'` 渲染居中灰 pill；否则按 `username === me.username` 分左右气泡。**所有插值都是 `{{ }}` 默认转义**，消息内容不做 `v-html`——这是对 PR01"输出端转义"结论的直接沿用。

### 7.4 WebSocket 客户端生命周期（ChatView.vue）

```js
onMounted(connectWs)          // 进入聊天页即连接
// 连接成功 → 先拉历史和名单（消除空窗）
ws.onopen = async () => { connected.value = true; retries = 0;
  await Promise.all([loadHistory(), loadUsers()]) }
// 消息分发
ws.onmessage = (e) => { const p = JSON.parse(e.data);
  if (p.type === 'message') appendMessage(p.data)        // 按 id 去重
  else if (p.type === 'online') { users.value = ...; onlineCount.value = ... } }
// 断线处理
ws.onclose = (event) => {
  if (event.code === 1008) { leaving = true; emit('logout'); return }  // 被踢 → 回登录页
  if (!leaving) { retries++; setTimeout(connectWs, Math.min(1000 * retries, 5000)) }  // 指数退避重连
}
```

三个细节值得注意：

- `leaving` 标志：主动登出前先置位，防止对服务端 1001 关闭触发无意义重连；
- 发送消息**不本地插入**，等服务端 WS 回显——所有客户端看到的消息顺序与服务端历史严格一致；
- 顶栏 `已连接 / 连接中…` 徽章、断线横幅、输入框禁用都由 `connected` 派生。

### 7.5 样式系统

`style.css` 顶部定义 CSS 变量（`--primary: #4f6df5` 等），全局只有一套明快浅色主题；布局为"header + sidebar + 主聊天区"的 flex 组合，720px 以下自动隐藏侧栏。改主题色只需改变量。

---

## 8. 测试指南

### 8.1 单元测试（后端核心逻辑）

```bash
mvn test        # ChatRoomTest：6 个用例
```

覆盖：登录产生系统消息、重名拒绝（含大小写变体）、敏感词替换、历史上限裁剪、离场清理、`historyAfter` 增量语义。`ChatRoom` 不依赖 servlet 容器（WS Session 相关方法在测试中自然为空转），所以能直接 new 出来测。

### 8.2 REST 冒烟（curl，见第 5 节示例）

### 8.3 WebSocket 行为测试（Node 脚本）

`npm i ws` 后写脚本验证五类行为：未登录握手收到 1008、连上即收在线名单、他人登录触发系统消息、REST 发的消息经 WS 到达、登出后连接被服务端以 1001 关闭。要点：

- `new WebSocket(url, { headers: { Cookie: 'JSESSIONID=...' } })` 模拟浏览器携带会话；
- 用 `fetch` 的 `Set-Cookie` 响应头提取 JSESSIONID。

### 8.4 浏览器端到端

单浏览器只有一个 Cookie 罐，两个标签页会是**同一个用户**（这本身验证了"同会话多标签页"场景）。验证双用户交互的技巧：浏览器里保持 alice，用 curl 维护 bob 的会话（`-c bob.jar`）登录/发消息/登出，观察浏览器实时收到对方的加入、发言、离开。本项目正是用这套方法完成端到端验收的。

---

## 9. 内嵌 Tomcat 装配要点（踩坑实录）

从"外部 Tomcat + WAR"切换到 `Tomcat.addContext()` 内嵌模式后，`conf/web.xml` 提供的默认行为全部消失，以下五个坑都在 `Main.java` 有对应处理，**每个都是实测真实发生过的问题**：

1. **必须显式注册 DefaultServlet**。没有它，所有未映射到业务 Servlet 的路径（静态文件、`/ws/chat` 升级请求）直接 404——WebSocket 升级依赖"请求先映射到某个 Servlet，过滤器才有机会拦截"。修复：`Tomcat.addServlet(ctx, "default", new DefaultServlet()).addMapping("/")`。

2. **必须手工补 MIME 映射**。没有 conf/web.xml 就没有 `.js → application/javascript` 的映射；`Content-Type` 为空的 JS 文件会被浏览器拒绝当作 ES module 执行，页面白屏。修复：`standardCtx.addMimeMapping(...)` 一组常用类型。

3. **HttpSessionListener 要按类名注册**。会话销毁事件从 lifecycle 监听器列表分发；实例方式注册（`addApplicationEventListener`）收不到 `sessionDestroyed`。按类名注册（等价 web.xml `<listener>`）并保留无参构造最可靠，于是监听器经 `ChatRoomHolder` 获取 ChatRoom。

4. **webapp 类加载器不含应用类路径**。按类名实例化监听器时抛 `ClassNotFoundException` 导致整个上下文启动失败。修复：`standardCtx.setParentClassLoader(Main.class.getClassLoader())`。

5. **异步 Remote 不允许重叠写入**。连续快速调用 `getAsyncRemote().sendText()` 会抛 `TEXT_FULL_WRITING`，消息静默丢失。修复：按连接排队 + SendHandler 回调链串行发送（见 4.4）。

> 学习价值：这些坑恰好解释了"外部 Tomcat 部署 WAR"时代那些"看不见的默认配置"都做了什么，是理解 Servlet 容器工作原理的好教材。

---

## 10. 二次开发指南

### 10.1 实战演练：新增接口 `GET /api/history/search?keyword=xx`

按固定四步走（约 15 行代码）：

**① store 层加查询方法**（`ChatRoom.java`）：

```java
public List<Message> searchHistory(String keyword) {
    synchronized (this) {
        return history.stream()
                .filter(m -> m.type().equals(Message.TYPE_CHAT)
                        && m.content().toLowerCase().contains(keyword.toLowerCase()))
                .toList();
    }
}
```

**② 新建 Servlet**（`servlet/SearchServlet.java`，或在 MessagesServlet 加映射）：

```java
public class SearchServlet extends HttpServlet {
    private final ChatRoom chatRoom;
    public SearchServlet(ChatRoom chatRoom) { this.chatRoom = chatRoom; }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String keyword = req.getParameter("keyword");
        if (keyword == null || keyword.isBlank()) {
            JsonUtil.write(resp, 400, Map.of("error", "keyword 不能为空"));
            return;
        }
        JsonUtil.write(resp, 200, Map.of("messages", chatRoom.searchHistory(keyword.trim())));
    }
}
```

**③ Main 注册**（第 8 步旁边加一行——AuthFilter 自动覆盖新路径，无需额外配置）：

```java
Tomcat.addServlet(ctx, "searchServlet", new SearchServlet(chatRoom)).addMapping("/api/history/search");
```

**④ 验证**：`mvn compile exec:java` 重启后 `curl -b a.jar 'localhost:8080/api/history/search?keyword=你好'`。

### 10.2 新增一种 WS 推送（以"正在输入"为例）

1. `ChatRoom` 加 `typing(String username)`：`broadcastPacket(Map.of("type", "typing", "data", Map.of("username", username)))`；
2. `ChatEndpoint.onMessage` 约定客户端发送文本 `"typing"` 即触发（记得防抖交给前端）；
3. 前端 `ChatView.ws.onmessage` 加分支，侧栏或气泡角标显示"xx 正在输入…"。

### 10.3 扩展练习建议（按难度递增）

| 练习 | 涉及改动 | 学到什么 |
| --- | --- | --- |
| 消息持久化（SQLite/H2） | `ChatRoom` 注入 DAO，启动时载入历史 | JDBC + 生命周期（对应课程后续内容） |
| 多房间 | URL 增加 `/{room}`，`ChatRoom` 变 `Map<String, Room>` | 领域建模、WS 路径参数 |
| 私聊/`@`提及 | 协议加 `to` 字段，广播改定向 | 定向推送（需要连接→用户反查） |
| 表情/图片消息 | REST 换 `multipart`（复习 PR02） | 二进制上传、消息多态 |
| 中英双语 UI | 前端 i18n 字典（对应 PR06 的 i18n） | 前端国际化 |
| Docker 化 | 后端 Dockerfile + 前端多阶段构建 | 容器化交付 |
| 打回 WAR 部署 | pom 改 war、Main 拆成纯 listener 装配 | 两种部署形态的等价转换 |

---

## 11. FAQ / 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Port 8080 was already in use` | 旧进程未退 | `lsof -ti:8080 \| xargs kill`，或 `FINAL_PORT=9090 mvn compile exec:java` |
| 打开首页 404 | dist 不存在（纯 API 模式） | 先 `npm run build`，或 `FINAL_STATIC_DIR` 指向构建产物 |
| 页面白屏、JS 不执行 | 自定义静态目录缺 MIME 映射 | 参考第 9 节坑 ②，在 Main 补 `addMimeMapping` |
| 一直显示"连接中…" | 后端没启动 / 开发模式没走 5173 | 确认后端日志与访问端口；WS 依赖 `/ws` 代理 |
| 登录报 409「已经在线了」 | 同名会话未过期 | 用别的用户名，或等会话超时（60 分钟）/ 重启后端；测试脚本务必 `POST /api/logout` 清理 |
| 改了 Java 代码不生效 | `exec:java` 无热加载 | 重启：`Ctrl-C` 后再 `mvn compile exec:java` |
| Maven 编译报 release 21 无效 | Maven 用的 JDK < 21 | `JAVA_HOME` 指向 JDK 21 |
| 测试间相互影响（409） | ChatRoom 是同一个 JVM 内状态 | 每个用例 new `ChatRoom`；集成测试用随机用户名 + 登出清理 |
| 消息偶发重复 | WS 重连与 REST 拉取重叠 | 正常现象，前端已按消息 id 去重 |

## 12. 附录：默认参数一览

| 参数 | 值 | 定义处 |
| --- | --- | --- |
| HTTP/WS 端口 | 8080（`FINAL_PORT` 可改） | `Main` |
| 会话超时 | 60 分钟 | `Main`（`setSessionTimeout`） |
| 消息历史上限 | 200 条 | `ChatRoom.MAX_HISTORY` |
| 单条消息长度 | ≤ 500 字符 | `MessagesServlet.MAX_CONTENT_LENGTH` |
| 用户名 | 2~20 字符（trim 后） | `LoginServlet` |
| 敏感词 | 去死 / 傻逼 / 笨蛋 → `**` | `ChatRoom.SENSITIVE_WORDS` |
| 重连退避 | 1s 起步，步进 1s，上限 5s | `ChatView` `connectWs` |
| 消息 id | 从 1 自增 | `ChatRoom.idGen` |
| 时间戳 | epoch 毫秒，前端 `zh-CN HH:mm` 本地化 | `Message.createdAt` / `formatTime` |
