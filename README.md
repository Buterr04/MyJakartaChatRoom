<div align="center"> 
<h1>MyJakartaChatRoom</h1>
<p>Jakarta EE 课程的收官项目：Java 后端 + Vite 前端的现代实时聊天室</p>
<p>
  <img src="https://img.shields.io/github/stars/Buterr04/MyJakartaChatRoom.svg?style=social">
  <img src="https://img.shields.io/github/issues/Buterr04/MyJakartaChatRoom.svg">
  <img src="https://img.shields.io/github/license/Buterr04/MyJakartaChatRoom.svg">
  <img src="https://img.shields.io/badge/Language-Java-blue?logo=java&logoColor=white">
  <img src="https://img.shields.io/badge/Framework-Jakarta%20EE-green?logo=jakartaee&logoColor=white">
  <img src="https://img.shields.io/badge/Frontend-Vite%20%2B%20Vue%203-pink?logo=vue.js&logoColor=white">
  </p>
  <img src=https://socialify.git.ci/Buterr04/MyJakartaChatRoom/image?custom_language=Java&issues=1&language=1&name=1&owner=1&stargazers=1&theme=Light />
</div>


> 📖 详细开发手册（架构解析 / 运行调试 / 协议规范 / 测试方法 / 踩坑实录 / 二次开发指南）见 [DEVELOPMENT.md](./DEVELOPMENT.md)。

前六个 PR（PR00~PR06）从 Servlet 一路学到 JSP/JSTL，全部是"服务端渲染 + 表单提交 + 外部 Tomcat"的传统形态。Final 在保留课程编程模型（纯 `jakarta.servlet` / `jakarta.websocket`，servlet、filter、listener 一个不少）的前提下，升级为**前后端分离架构**：

- 后端：内嵌 Tomcat 10.1 + REST API + WebSocket 实时推送，`mvn compile exec:java` 一条命令启动，不再依赖外部安装的 Tomcat；
- 前端：Vite + Vue 3 单页应用，开发时走 Vite dev server（API/WS 自动代理），生产构建后直接由同一个 Tomcat 服务。

## 功能

- 用户注册与密码登录：用户名忽略大小写唯一，显示名称用于聊天展示，密码使用随机盐 PBKDF2 哈希保存
- SQLite 持久化账户与最近 200 条聊天/系统消息，重启后恢复历史；在线用户和 WebSocket 连接不跨重启恢复
- HttpSession 保持登录态，刷新页面自动恢复；同一账户不能重复在线
- 实时聊天：发消息走 REST（拿到明确的 201/400/401 状态码），新消息经 WebSocket 广播给所有在线用户
- 系统消息：成员加入 / 离开实时广播
- 在线名单侧栏：人数与列表随登录 / 登出 / 会话超时实时更新
- 敏感词过滤（沿袭 PR04）、消息历史上限 200 条（对应 PR03 的 maxMessageCount）、消息长度校验
- 断线自动重连（指数退避），会话过期被服务端拒绝（关闭码 1008）时自动回到登录页
- XSS 防护：消息以原始文本存储，Vue 插值默认转义输出（PR01 的经验：在输出端转义）

## 快速开始

需要 JDK 21+ 和 Node.js 20.19+/22+（构建前端用）。

### 生产模式（单进程）

```bash
# 1. 构建前端
cd frontend
npm install
npm run build          # 产物输出到 frontend/dist

# 2. 启动后端（自动探测 ../frontend/dist 作为静态目录）
cd ../backend
mvn compile exec:java

# 打开 http://localhost:8080
```

### 开发模式（前后端热更新）

```bash
# 终端 1：后端（此时 dist 不存在，自动进入纯 API 模式）
cd backend && mvn compile exec:java

# 终端 2：前端 dev server，/api 与 /ws 自动代理到 8080
cd frontend && npm install && npm run dev

# 打开 http://localhost:5173
```

可选环境变量：`FINAL_PORT`（默认 8080）、`FINAL_STATIC_DIR`（静态目录，默认自动探测 `../frontend/dist` → `frontend/dist` → 纯 API 模式）、`FINAL_DB_PATH`（默认 `final-chatroom.db`，相对于后端启动时的工作目录）。

## 目录结构

```
Final/
├── backend/                          # Java 后端（Maven, Java 21）
│   ├── pom.xml                       # 内嵌 Tomcat 10.1 + WebSocket + Jackson + SQLite + JUnit
│   └── src/main/java/site/buterr/finalchat/
│       ├── Main.java                 # 启动入口：装配 Tomcat / Servlet / Filter / Listener / WS
│       ├── servlet/                  # REST API（Register / Login / Me / Logout / Users / Messages）
│       ├── filter/AuthFilter.java    # 登录态过滤器（对应 PR04）
│       ├── listener/                 # 会话销毁 → 用户离场（对应 PR04~06 的 SessionLifecycleListener）
│       ├── chat/                     # WebSocket 端点 /ws/chat + 握手配置器
│       ├── model/                    # Account / User / Message（不可变 record）
│       ├── store/ChatRoom.java       # 运行时状态：在线用户 + 消息历史 + WS 连接登记 + 广播
│       ├── store/ChatRepository.java # SQLite 账户与消息持久化
│       ├── store/PasswordHasher.java # 随机盐 PBKDF2 密码哈希
│       └── util/JsonUtil.java        # JSON 读写封装
│   └── src/test/...                  # ChatRoom 与 SQLite 持久化单元测试
└── frontend/                         # Vite + Vue 3 前端
    ├── vite.config.js                # /api、/ws 开发代理
    └── src/
        ├── App.vue                   # 会话恢复 + 注册/登录/聊天视图切换
        └── components/
            ├── LoginView.vue         # 登录/注册卡片
            └── ChatView.vue          # 聊天主界面 + WebSocket 客户端（重连/去重/自动滚动）
```

## REST API

| 方法 | 路径 | 说明 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| POST | `/api/register` | 注册，`{"username":"...","displayName":"...","password":"..."}` | 201 + 账户资料 | 400 校验失败 / 409 用户名已存在 |
| POST | `/api/login` | 登录，`{"username":"...","password":"..."}` | 200 + 用户信息 | 400 校验失败 / 401 凭据错误 / 409 已在线 |
| GET | `/api/me` | 当前登录用户（恢复会话用） | 200 | 401 |
| POST | `/api/logout` | 登出（销毁会话，触发离场广播） | 200 | — |
| GET | `/api/users` | 在线用户列表 | 200 | 401 |
| GET | `/api/messages?after={id}` | 历史消息（after 为增量游标） | 200 | 401 |
| POST | `/api/messages` | 发送消息，`{"content":"..."}` | 201 | 400 / 401 |

## WebSocket 协议（`/ws/chat`）

浏览器握手时自动携带 JSESSIONID Cookie，服务端在握手阶段把 HTTP 会话绑定到该连接；未登录的握手会被立即以 1008 关闭。

服务端 → 客户端：

```jsonc
// 新消息（chat=用户发言，system=系统提示）
{"type":"message","data":{"id":3,"type":"chat","username":"alice","displayName":"小明","content":"你好","createdAt":1792810797079}}
// 在线名单（登录/登出/超时时推送）
{"type":"online","data":{"count":2,"users":[{"username":"alice","displayName":"小明","loginAt":...}]}}
```

客户端 → 服务端：`"ping"`，服务端回 `"pong"`（保活探测）。

## 与 PR00~PR06 的知识对照

| 课程知识点 | 在 Final 中的位置 |
| --- | --- |
| PR00 Servlet 基础 | 全部 REST Servlet |
| PR01 线程安全集合 / HTML 转义 | `ChatRoom` 的并发容器；消息由 Vue 插值转义输出 |
| PR02 HTTP 语义（状态码） | REST API 的 200/201/400/401/409 全套状态码 |
| PR03 session / application 作用域 | HttpSession 登录态；全局状态收拢为显式的 `ChatRoom` 对象 |
| PR03 maxMessageCount | `ChatRoom.MAX_HISTORY = 200`，超出丢弃最老 |
| PR04 AuthFilter | `filter/AuthFilter.java`（未登录返回 401 JSON 而非重定向） |
| PR04 SessionLifecycleListener | `listener/SessionLifecycleListener.java`（会话销毁 → 离场清理） |
| PR04 敏感词过滤 | `ChatRoom.filterSensitive` |
| PR05 在线人数 | 由在线用户表派生，不再手工加减计数器 |
| PR06 前后端职责分离 | 服务端只出 JSON，渲染完全交给前端 |

**新增技能**：WebSocket 实时推送、Vite/Vue 3 前后端分离开发、内嵌 Tomcat 装配、Java record + Jackson。

## 测试

```bash
cd backend
mvn test        # 8 项：聊天室规则、账户认证、消息持久化与历史上限
```
