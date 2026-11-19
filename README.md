<div align="center"> 
<h1>MyJakartaChatRoom</h1>
<p>Java 后端 + Vite 前端的实时聊天室</p>
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

项目采用**前后端分离架构**，后端仍是纯 `jakarta.servlet` / `jakarta.websocket` 编程模型（servlet、filter、listener 一个不少）：

- 后端：内嵌 Tomcat 10.1 + REST API + WebSocket 实时推送，`mvn compile exec:java` 一条命令启动，不再依赖外部安装的 Tomcat；
- 前端：Vite + Vue 3 单页应用，开发时走 Vite dev server（API/WS 自动代理），生产构建后直接由同一个 Tomcat 服务。

## 功能

- 用户注册与密码登录：用户名注册查重忽略大小写（`Alice`/`alice` 视为同名），登录校验区分大小写；显示名称用于聊天展示，密码使用随机盐 PBKDF2 哈希保存
- SQLite 永久保留账户与全部聊天/系统消息，重启后恢复历史；前端默认不显示历史，触屏下滑 / 鼠标向下滚动才加载最近 50 条并分页，历史不显示系统消息（系统消息仅在线时通过 WS 实时可见）；在线用户和 WebSocket 连接不跨重启恢复
- 个人主页：展示用户名、显示名称与权限，支持上传或恢复默认头像、校验当前密码后修改登录密码；头像在页头和在线名单中同步显示
- 角色权限：内置超级管理员 `Admin`（密码 `admin`）；超级管理员可任命/取消聊天室管理员、删除消息、管理敏感词与 AI，并可禁言聊天室管理员；聊天室管理员有「用户管理」但只能禁言普通用户；在线列表对普通用户/聊天室管理员隐藏 Admin，Admin 自己可见
- HttpSession 保持登录态，刷新页面自动恢复；在线状态由 `/ws/chat` 连接派生，同一账户同一时刻只能保留一个 HTTP 会话的实时连接；WS 断开后 30 秒宽限，显式登出立即离线；重复登录可选择强制挤掉旧会话
- 实时聊天：发消息走 REST（拿到明确的 201/400/401 状态码），新消息经 WebSocket 广播给所有在线用户
- 聊天室名称/图标：管理员可在「房间设置」修改名称、上传 ≤1MB 图标，保存后所有在线用户实时更新
- Emoji 表情：输入框内置 64 个常用表情，点击插入光标位置，随消息一起发送
- 图片消息：像微信表情一样内嵌展示，支持 10MB 以内的 PNG、JPEG、GIF、WebP，GIF 保留动画，点击可查看原图
- AI 助手：消息开头输入 `@AI` 即可提问；支持最近聊天上下文，回复异步生成并实时广播，不阻塞其他消息
- AI 后台配置：仅内置超级管理员 `Admin` 可维护兼容 `chat/completions` 的接口地址、模型、API Key、系统提示词与启用状态，并可测试连接
- 系统消息：成员加入 / 离开实时广播
- 在线名单侧栏：人数与列表随 WebSocket 连接建立 / 断开实时更新；同一 HTTP 会话的多个标签页只算一个在线用户，最后一个连接断开 30 秒后离线（显式登出立即离线）
- 超级管理员统计：今日消息、历史总数、今日登录人数/总人数、今日发言 TOP10、按天/周/月查询的消息柱状图，全部排除 system 消息
- 敏感词过滤（仅超级管理员可在后台增删词库）、消息永久保留 + 游标分页、内存热窗口 200 条、消息长度校验
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

首次启动会自动创建默认超级管理员：用户名 `Admin`，密码 `admin`。

### 配置 AI 助手

1. 使用 `Admin` 登录，点击页头的“AI 设置”。
2. 填写兼容 OpenAI `chat/completions` 的接口地址（如 `https://api.openai.com/v1`）、模型和 API Key，然后保存并测试连接。
3. 勾选“启用 AI 助手”。任何登录用户都可以点击输入区的 `@AI`，或直接发送 `@AI 你的问题`。

接口地址也可以填写完整的 `/chat/completions` 路径。API Key 保存在本地 SQLite，GET 配置接口只返回 `hasApiKey`，不会把密钥回传给浏览器；编辑时留空表示保持原密钥。AI 请求携带最近 8 条文字聊天作为上下文，连接超时 10 秒、请求超时 60 秒。

### 开发模式（前后端热更新）

```bash
# 终端 1：后端（此时 dist 不存在，自动进入纯 API 模式）
cd backend && mvn compile exec:java

# 终端 2：前端 dev server，/api 与 /ws 自动代理到 8080
cd frontend && npm install && npm run dev

# 打开 http://localhost:5173
```

可选环境变量：`APP_PORT`（默认 8080）、`APP_STATIC_DIR`（静态目录，默认自动探测 `../frontend/dist` → `frontend/dist` → 纯 API 模式）、`APP_DB_PATH`（默认 `chatroom.db`）、`APP_UPLOAD_DIR`（默认 `uploads`，保存聊天图片）、`APP_CONFIG_DIR`（默认 `config`，保存 `sensitive-words.json`、`ai-settings.json` 与 `room.json`；AI 文件含 API Key，已被 `.gitignore` 忽略）；文件路径均相对于后端启动目录。

## 目录结构

```
MyJakartaChatRoom/
├── backend/                          # Java 后端（Maven, Java 21）
│   ├── pom.xml                       # 内嵌 Tomcat 10.1 + WebSocket + Jackson + SQLite + JUnit
│   └── src/main/java/site/buterr/chatroom/
│       ├── Main.java                 # 启动入口：装配 Tomcat / Servlet / Filter / Listener / WS
│       ├── assistant/                # AI 请求调度、上下文构造、HTTP 调用与错误转换
│       ├── servlet/                  # REST API（Register / Login / Me / Logout / Users / Messages）
│       ├── filter/                   # AuthFilter 登录态 + AdminFilter / SuperAdminFilter 权限守卫
│       ├── listener/                 # 会话销毁 → 用户离场（对应 PR04~06 的 SessionLifecycleListener）
│       ├── chat/                     # WebSocket 端点 /ws/chat + 握手配置器
│       ├── model/                    # Account / User / Message（不可变 record）
│       ├── store/ChatRoom.java       # 运行时状态：在线用户 + 消息历史 + WS 连接登记 + 广播
│       ├── store/ChatRepository.java # SQLite 账户、消息与旧配置迁移
│       ├── store/LocalConfigStore.java # config/*.json（敏感词 / AI / 房间品牌）
│       ├── store/PasswordHasher.java # 随机盐 PBKDF2 密码哈希
│       ├── store/ImageStorage.java   # 聊天图片格式校验、UUID 文件存取与清理
│       └── util/JsonUtil.java        # JSON 读写封装
│   └── src/test/...                  # ChatRoom 与 SQLite 持久化单元测试
└── frontend/                         # Vite + Vue 3 前端
    ├── vite.config.js                # /api、/ws 开发代理
    └── src/
        ├── App.vue                   # 会话恢复 + 注册/登录/聊天视图切换
        └── components/
            ├── LoginView.vue         # 登录/注册卡片
            ├── AiSettingsDialog.vue  # Admin 专用 AI 配置与连接测试
            └── ChatView.vue          # 聊天主界面 + WebSocket 客户端（重连/去重/自动滚动）
```

## REST API

| 方法 | 路径 | 说明 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| POST | `/api/register` | 注册，`{"username":"...","displayName":"...","password":"..."}` | 201 + 账户资料 | 400 校验失败 / 409 用户名已存在 |
| POST | `/api/login` | 登录，`{"username":"...","password":"...","force":false}`；`force=true` 可挤掉旧会话 | 200 + 用户信息 | 400 校验失败 / 401 凭据错误 / 409 已在其他设备登录（带 `code`，可强制登录） |
| GET | `/api/me` | 当前登录用户（恢复会话用） | 200 | 401 |
| GET | `/api/websocket-status` | WS 握手被拒时查询原因（浏览器可能只报 1006） | 200 `{ok:true}` | 401 / 409 已被占用 |
| PUT | `/api/me/avatar` | 更新头像，`{"avatar":"data:image/png;base64,..."}`；null 恢复默认 | 200 | 400 / 401 |
| PUT | `/api/me/password` | 修改密码，`{"currentPassword":"...","newPassword":"..."}`；新密码长度 8~128 个字符 | 200 | 400 旧密码错误或格式不合法 / 401 |
| POST | `/api/logout` | 登出（立即销毁会话、广播离场） | 200 | — |
| GET | `/api/users` | 在线用户列表 | 200 | 401 |
| GET | `/api/messages?limit=50`、`?before={id}`、`?after={id}` | 历史消息分页（默认最新 50，limit 1~200） | 200 `{messages, hasMore}` | 400 / 401 |
| POST | `/api/messages` | 发送消息，`{"content":"..."}` | 201 | 400 / 401 / 403 禁言 |
| POST | `/api/messages/image` | multipart 字段 `image`，发送图片消息 | 201 | 400 / 401 / 403 / 413 |
| GET | `/api/images/{id}` | 读取聊天图片（登录用户） | 200 图片字节 | 401 / 404 |
| DELETE | `/api/super-admin/messages/{id}` | 超级管理员删除聊天消息 | 200 | 401 / 403 / 404 |
| POST | `/api/admin/users/{username}/mute` | 禁言普通用户，`{"minutes":10}`；0 表示解禁 | 200 | 400 / 401 / 403 / 404 |
| GET | `/api/admin/users` | 管理员 / 超级管理员；列表隐藏 Admin 与当前登录账户 | 200 | 401 / 403 |
| POST | `/api/admin/users/{username}/role` | 超级管理员设置/取消聊天室管理员 | 200 | 400 / 401 / 403 / 404 |
| GET/POST/DELETE | `/api/super-admin/sensitive-words` | 超级管理员管理敏感词 | 200/201 | 400 / 401 / 403 / 404 / 409 |
| GET | `/api/super-admin/statistics?from=&to=&granularity=` | 超级管理员消息统计；`granularity=day/week/month`；排除 system | 200 | 400 / 401 / 403 |
| GET | `/api/room` | 公开聊天室名称/图标 | 200 | — |
| PUT | `/api/admin/room` | 管理员/超级管理员修改名称、图标 | 200 | 400 / 401 / 403 |
| GET | `/api/ai/status` | 已登录用户读取 AI 开关（控制 @AI 按钮） | 200 | 401 |
| GET | `/api/avatars?usernames=a,b` | 批量查询历史消息作者头像（最多 100 个） | 200 | 400 / 401 |
| GET | `/api/super-admin/ai/settings` | Admin 查询 AI 配置，不返回 API Key | 200 | 401 / 403 |
| PUT | `/api/super-admin/ai/settings` | Admin 保存 AI 配置；Key 留空保持，`clearApiKey:true` 清除 | 200 | 400 / 401 / 403 |
| POST | `/api/super-admin/ai/settings/test` | Admin 使用已保存配置测试真实接口 | 200 + AI 回复 | 401 / 403 / 502 |

## WebSocket 协议（`/ws/chat`）

浏览器握手时自动携带 JSESSIONID Cookie，服务端在握手阶段把 HTTP 会话绑定到该连接；未登录的握手会被立即以 1008 关闭。

服务端 → 客户端：

```jsonc
// 新消息（chat=用户发言，system=系统提示）
{"type":"message","data":{"id":3,"type":"chat","username":"alice","displayName":"小明","content":"你好","createdAt":1792810797079}}
// 在线名单（WebSocket 连接建立/断开、登出或会话超时时推送）
{"type":"online","data":{"count":2,"users":[{"username":"alice","displayName":"小明","loginAt":...}]}}
// 管理员删除聊天消息
{"type":"messageDeleted","data":{"id":3}}
// 禁言或解禁后的账号状态
{"type":"accountUpdated","data":{"username":"alice","displayName":"小明","role":"USER","mutedUntil":1794793622541}}
```

客户端 → 服务端：`"ping"`，服务端回 `"pong"`（保活探测）。

## 与 PR00~PR06 的知识对照

| 课程知识点 | 在项目中的位置 |
| --- | --- |
| PR00 Servlet 基础 | 全部 REST Servlet |
| PR01 线程安全集合 / HTML 转义 | `ChatRoom` 的并发容器；消息由 Vue 插值转义输出 |
| PR02 HTTP 语义（状态码） | REST API 的 200/201/400/401/403/404/409 状态码 |
| PR03 session / application 作用域 | HttpSession 登录态；全局状态收拢为显式的 `ChatRoom` 对象 |
| PR03 maxMessageCount | `ChatRoom.MAX_HISTORY = 200`，超出丢弃最老 |
| PR04 AuthFilter | `filter/AuthFilter.java`（未登录返回 401 JSON 而非重定向） |
| PR04 SessionLifecycleListener | `listener/SessionLifecycleListener.java`（会话销毁 → 离场清理） |
| PR04 敏感词过滤 | `ChatRoom.filterSensitive` |
| PR05 在线人数 | 由 WebSocket 连接状态派生，不再手工加减计数器 |
| PR06 前后端职责分离 | 服务端只出 JSON，渲染完全交给前端 |

**新增技能**：WebSocket 实时推送、Vite/Vue 3 前后端分离开发、内嵌 Tomcat 装配、Java record + Jackson。

## 测试

```bash
cd backend
mvn test        # 43 项单元测试：聊天室规则、角色/头像/密码、图片存取、AI 请求与回复、统计、旧库迁移及持久化
mvn verify      # 43 单元 + 11 集成：Servlet/Filter/WebSocket/SSO/头像查询
```
