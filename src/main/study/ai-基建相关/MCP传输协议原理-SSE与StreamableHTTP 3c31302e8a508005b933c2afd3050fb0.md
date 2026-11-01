# MCP传输协议原理-SSE与StreamableHTTP

# MCP 传输协议原理详解：SSE、Streamable HTTP 与 stdio

> 本文整理自对 MCP（Model Context Protocol）传输层机制的深入分析，涵盖：
- `sessionId` 与 JSON-RPC `id` 的区别
- stdio 传输为什么不需要 `sessionId`
- `sessionId` 是否每次请求都携带
- Streamable HTTP 与 HTTP+SSE 的核心区别
- 两种远程传输协议的完整建链、握手与后续流程
> 

---

## 目录

1. [sessionId 与 id：两个不同协议层的标识符](about:blank#%E4%B8%80sessionid-%E4%B8%8E-id%E4%B8%A4%E4%B8%AA%E4%B8%8D%E5%90%8C%E5%8D%8F%E8%AE%AE%E5%B1%82%E7%9A%84%E6%A0%87%E8%AF%86%E7%AC%A6)
2. [sessionId 详解：解决 SSE 的”半双工”问题](about:blank#%E4%BA%8Csessionid-%E8%AF%A6%E8%A7%A3%E8%A7%A3%E5%86%B3-sse-%E7%9A%84%E5%8D%8A%E5%8F%8C%E5%B7%A5%E9%97%AE%E9%A2%98)
3. [stdio 为什么不需要 sessionId](about:blank#%E4%B8%89stdio-%E4%B8%BA%E4%BB%80%E4%B9%88%E4%B8%8D%E9%9C%80%E8%A6%81-sessionid)
4. [sessionId 是否每次请求都带上](about:blank#%E5%9B%9Bsessionid-%E6%98%AF%E5%90%A6%E6%AF%8F%E6%AC%A1%E8%AF%B7%E6%B1%82%E9%83%BD%E5%B8%A6%E4%B8%8A)
5. [Streamable HTTP 与 HTTP+SSE 的核心区别](about:blank#%E4%BA%94streamable-http-%E4%B8%8E-http-sse-%E7%9A%84%E6%A0%B8%E5%BF%83%E5%8C%BA%E5%88%AB)
6. [HTTP+SSE 完整流程](about:blank#%E5%85%ADhttp-sse-%E6%97%A7%E5%8D%8F%E8%AE%AE%E5%AE%8C%E6%95%B4%E6%B5%81%E7%A8%8B)
7. [Streamable HTTP 完整流程](about:blank#%E4%B8%83streamable-http%E6%96%B0%E5%8D%8F%E8%AE%AE%E5%AE%8C%E6%95%B4%E6%B5%81%E7%A8%8B)
8. [总结对比](about:blank#%E5%85%AB%E6%80%BB%E7%BB%93%E5%AF%B9%E6%AF%94)

---

## 一、sessionId 与 id：两个不同协议层的标识符

### 核心结论

|  | `sessionId` | `id` |
| --- | --- | --- |
| **所属层** | **传输层**（Transport Layer） | **消息层**（JSON-RPC 2.0 协议层） |
| **作用范围** | 标识一条”连接/会话” | 标识一次”请求-响应”配对 |
| **生命周期** | 整个 SSE 会话期间（从连接建立到断开） | 单次请求，收到响应即结束 |
| **由谁生成** | 服务端生成，通过 `endpoint` 事件下发 | 客户端生成，自增或随机 |
| **stdio 是否需要** | ❌ 不需要 | ✅ 必须有 |

### 直观类比

- **`sessionId`** 像餐厅的**桌号**：服务员（服务端）同时服务很多桌（客户端），上菜时必须知道送到哪一桌。如果你包场了整个餐厅（stdio 独占进程），桌号就没有意义了。
- **`id`** 像每道菜的**订单号**：即使是同一桌，你点了 3 道菜，菜上来的顺序和点单顺序可能不同，需要订单号对上”这是我点的哪道菜”。无论是包场还是拼桌，订单号都是必需的。

---

## 二、sessionId 详解：解决 SSE 的”半双工”问题

### 1. SSE 的天然缺陷

SSE（Server-Sent Events）是**单向通道**：只能服务端 → 客户端推送数据，客户端无法通过这条通道给服务端发消息。

所以 MCP 的 SSE 传输实际上是”**双通道拼接**”：

```
通道 1（下行）：GET  /sse                    ← 服务端推送响应/通知（长连接）
通道 2（上行）：POST /mcp-servers/xxx?sessionId=...  ← 客户端发送请求（普通 HTTP）
```

### 2. sessionId 的作用：把两条通道”粘”在一起

问题：服务端可能同时被 100 个客户端连接。当某个 POST 请求进来时，服务端怎么知道**这个请求的响应该推送到哪条 SSE 连接上**？

答案就是 `sessionId`。握手过程：

```
event: endpoint
data: /mcp-servers/soul-haven-character-service-prod?sessionId=fb0ca2fc-17ce-4716-8224-a1ee88788d40
```

流程：

1. 客户端发起 `GET /sse` 建立 SSE 长连接
2. 服务端**立即生成一个唯一的 sessionId**，并通过第一个 `endpoint` 事件告诉客户端：“你以后所有 POST 请求都发到这个带 sessionId 的 URL”
3. 客户端后续所有 JSON-RPC 消息都 POST 到该 URL
4. 服务端收到 POST 后，根据 `sessionId` 查表找到对应的 SSE 连接，把响应**从 SSE 通道推回去**（POST 本身通常只返回 `202 Accepted`，真正的响应走 SSE）

```
客户端                                服务端
  │── GET /sse ────────────────────────→│  建立长连接
  │←─ event: endpoint (含 sessionId) ───│  分配会话
  │                                      │
  │── POST /...?sessionId=xxx ─────────→│  发 initialize 请求
  │←─ 202 Accepted ─────────────────────│  （HTTP 层只是"收到了"）
  │←═ SSE 推送 initialize 的响应 ═══════│  （真正的响应走 SSE 通道）
```

### 3. sessionId 还承载会话状态

MCP 是**有状态协议**：`initialize` 握手后协商的能力（capabilities）、订阅关系、采样上下文等都绑定在会话上。服务端用 `sessionId` 作为 key 存储这些状态。

---

## 三、stdio 为什么不需要 sessionId

### stdio 的工作方式

客户端（如 IDE）**自己启动**一个 MCP 服务器子进程：

```
客户端进程 ──fork/spawn──→ MCP 服务器子进程
     │                            │
     ├── 写入子进程 stdin ────────→│  （上行：请求）
     │←──── 读取子进程 stdout ─────┤  （下行：响应）
```

三个理由：

1. **连接即会话**：这个子进程从出生起就只服务这一个客户端。管道（pipe）本身就唯一标识了这个会话，物理上不可能有第二个客户端混进来。
2. **不需要”寻址”**：SSE 场景服务端要在 N 条连接中找到正确的那条来推送响应；stdio 场景响应只有一个去处——stdout，写出去必然到达正确的客户端。
3. **状态直接存进程内存**：一个客户端 = 一个进程，会话状态就是进程状态，进程退出会话就结束，天然隔离。

**一句话总结**：`sessionId` 是为了在”多客户端共享一个 HTTP 服务端”时区分会话而存在的；stdio 是”一客户端一进程”的独占模型，不存在区分的需求。

### stdio 中的 id 是什么作用？

**和 SSE 中完全一样**。因为 `id` 属于 JSON-RPC 消息层，与底层传输方式无关。

即使在 stdio 中，客户端也可以并发发送多个请求（连续往 stdin 写多行 JSON），服务器处理耗时不同，响应顺序同样可能乱序：

```
stdin:  {"id": 1, "method": "tools/call", "params": {...}}   ← 耗时 5 秒的工具
stdin:  {"id": 2, "method": "tools/list"}                     ← 很快

stdout: {"id": 2, "result": {...}}   ← 先回来
stdout: {"id": 1, "result": {...}}   ← 后回来
```

没有 `id`，客户端就无法知道哪个结果对应哪个调用。

### 补充：没有 id 的消息是”通知”

JSON-RPC 中不带 `id` 的消息叫 **notification**（如 `notifications/initialized`），表示”我只是告诉你一声，不需要你回复”，因此也无从配对。

---

## 四、sessionId 是否每次请求都带上

**是的，SSE 传输中每次 POST 请求都会带上 `sessionId`。**

### 为什么每次都要带？

上行通道（POST）是**无状态的普通 HTTP 请求**，每个 POST 之间没有任何天然关联。服务端收到一个 POST 时，面对的可能是来自 100 个不同客户端中任何一个的消息。如果这次请求不带 `sessionId`，服务端就无法知道：

1. 这个消息属于哪个会话（该用哪份会话状态处理）
2. 处理结果该推送到哪条 SSE 连接上

### 实际的消息流示例

```
① GET /sse
   ← event: endpoint
     data: /messages?sessionId=fb0ca2fc-...        ← 服务端下发完整 URL

② POST /messages?sessionId=fb0ca2fc-...            ← initialize
③ POST /messages?sessionId=fb0ca2fc-...            ← notifications/initialized
④ POST /messages?sessionId=fb0ca2fc-...            ← tools/list
⑤ POST /messages?sessionId=fb0ca2fc-...            ← tools/call
   ...每一次都带
```

实现细节：客户端 SDK 通常并不是”每次手动拼接 sessionId”，而是**直接把 `endpoint` 事件下发的整个 URL 存下来**，之后所有请求都发往这个 URL。`sessionId` 是作为 URL 的一部分被”自然地”带上的。

### 对比：GET 下行连接只”绑定”一次

| 通道 | 会话标识方式 |
| --- | --- |
| 下行 SSE 长连接 | 建连时绑定一次，连接本身就是标识 |
| 上行 POST 请求 | **每次请求显式携带 `sessionId`** |
- 服务端在接受 `GET /sse` 连接的那一刻就生成了 sessionId，并把「sessionId → 这条 TCP 连接」的映射存进内存表
- 只要连接不断，这个绑定关系一直有效
- 连接断开时，服务端通常会清理该会话（或保留一段时间等待重连，取决于实现）

### 如果不带或者带错了会怎样？

- **缺少 `sessionId`** → 返回 `400 Bad Request`
- **`sessionId` 不存在或已过期**（比如 SSE 连接已断开、服务端重启导致内存映射丢失）→ 返回 `404 Not Found`，客户端需要重新走 `GET /sse` 建立新会话、重新 `initialize`

生产问题提示：**SSE 传输的服务端做负载均衡时必须开启会话亲和（sticky session）**——因为 sessionId 映射存在某台实例的内存里，POST 请求如果被路由到另一台实例，就会因找不到会话而 404。

---

## 五、Streamable HTTP 与 HTTP+SSE 的核心区别

MCP 的远程传输经历了一次重要演进：**HTTP+SSE**（2024-11-05 规范）→ **Streamable HTTP**（2025-03-26 规范起）。

### 核心区别总览

| 维度 | HTTP+SSE（旧） | Streamable HTTP（新） |
| --- | --- | --- |
| **端点数量** | **两个**：`GET /sse`（下行）+ `POST /messages`（上行） | **一个**：`/mcp`，GET/POST/DELETE 都打它 |
| **SSE 长连接** | **必须**，先建 SSE 才能通信 | **可选**，按需升级 |
| **会话标识** | URL query：`?sessionId=xxx` | HTTP 头：`Mcp-Session-Id: xxx` |
| **会话建立时机** | `GET /sse` 建连时，通过 `endpoint` 事件下发 | `initialize` 响应时，通过响应头下发 |
| **响应返回方式** | POST 只回 `202`，真正响应**绕道 SSE 通道**推回 | 响应**直接在 POST 的 HTTP 响应体**里返回（JSON 或 SSE 流） |
| **无状态部署** | 不可能（必须维持长连接） | 可以完全无状态（纯 request/response） |
| **断线恢复** | 无标准机制，断了重来 | 支持 `Last-Event-ID` 断点续传 |
| **负载均衡** | 必须 sticky session | 无状态模式下可任意路由 |
| **基础设施兼容性** | 差（长连接易被代理/网关/Serverless 掐断） | 好（就是普通 HTTP 请求） |

**一句话概括本质区别**：

- HTTP+SSE 是”**双通道拼接**”——请求走一条路，响应走另一条路，靠 sessionId 缝合；
- Streamable HTTP 是”**单端点自适应**”——响应原路返回，同一个 POST 既能返回普通 JSON，也能就地升级成 SSE 流，长连接从”前提”变成了”可选项”。

---

## 六、HTTP+SSE（旧协议）完整流程

```
客户端                                         服务端
  │                                              │
  │ ① GET /sse                                   │
  │   Accept: text/event-stream                  │
  │─────────────────────────────────────────────→│
  │←═════ 200, Content-Type: text/event-stream ══│  长连接建立
  │←═ event: endpoint                        ════│
  │   data: /messages?sessionId=abc123           │  ② 下发上行地址+会话ID
  │                                              │
  │ ③ POST /messages?sessionId=abc123            │
  │   {"id":1,"method":"initialize",...}         │
  │─────────────────────────────────────────────→│
  │←──── 202 Accepted（空响应）──────────────────│  HTTP层只表示"收到"
  │←═ event: message                         ════│
  │   data: {"id":1,"result":{capabilities..}}   │  ④ 真正响应走SSE推回
  │                                              │
  │ ⑤ POST /messages?sessionId=abc123            │
  │   {"method":"notifications/initialized"}     │
  │─────────────────────────────────────────────→│
  │←──── 202 Accepted ───────────────────────────│  握手完成
  │                                              │
  │ ⑥ POST /messages?sessionId=abc123            │
  │   {"id":2,"method":"tools/call",...}         │
  │─────────────────────────────────────────────→│
  │←──── 202 Accepted ───────────────────────────│
  │←═ data: {"method":"notifications/progress"} ═│  进度通知走SSE
  │←═ data: {"id":2,"result":{...}}          ════│  结果走SSE
  │                                              │
  │ ⑦ SSE连接断开 = 会话终结，需从①重新开始       │
```

关键特征：

- **所有下行数据（响应、通知、服务端主动请求）都挤在那一条 SSE 长连接上**
- POST 的 HTTP 响应永远是没有实质内容的 `202`
- SSE 连接一断，sessionId 失效，全部重来

### 这个设计的痛点

1. **长连接是硬依赖**：哪怕客户端只想调一次工具，也得先维持一条 SSE 长连接。
2. **代理/网关不友好**：Nginx、云网关、Serverless 平台（Lambda 等）对空闲长连接有超时限制，动辄掐断。
3. **服务端必须有状态**：sessionId → 连接的映射在内存里，无法水平扩展（除非 sticky session）。
4. **无恢复机制**：断线时途中推送的消息永久丢失。

---

## 七、Streamable HTTP（新协议）完整流程

### 阶段 1：初始化握手（建立会话）

```
客户端                                         服务端
  │ ① POST /mcp                                  │
  │   Accept: application/json, text/event-stream│  ← 必须同时声明两种
  │   {"id":1,"method":"initialize","params":{   │
  │     "protocolVersion":"2025-03-26",...}}     │
  │─────────────────────────────────────────────→│
  │←──── 200 OK ─────────────────────────────────│
  │      Mcp-Session-Id: abc123          ←───────│  ② 会话ID在响应头下发
  │      Content-Type: application/json          │
  │      {"id":1,"result":{"capabilities":...}}  │  ③ 响应直接在body返回！
  │                                              │
  │ ④ POST /mcp                                  │
  │   Mcp-Session-Id: abc123                     │  ← 之后每个请求都带此头
  │   {"method":"notifications/initialized"}     │
  │─────────────────────────────────────────────→│
  │←──── 202 Accepted ───────────────────────────│  握手完成
```

与旧协议的两个决定性差异：**没有预先的 GET 建连步骤**，第一个动作就是 POST `initialize`；且响应**直接在这个 POST 的响应体里返回**。

### 阶段 2：常规请求 —— 服务端自适应选择响应形态

客户端每个 POST 都声明 `Accept: application/json, text/event-stream`，**服务端根据处理特性二选一**：

**情形 A：快请求 → 直接返回 JSON（像普通 REST API）**

```
  │ POST /mcp  (Mcp-Session-Id: abc123)          │
  │   {"id":2,"method":"tools/list"}             │
  │─────────────────────────────────────────────→│
  │←── 200, Content-Type: application/json ──────│
  │    {"id":2,"result":{"tools":[...]}}         │  一来一回，结束
```

**情形 B：慢请求/需要中途推送 → 就地升级为 SSE 流**

```
  │ POST /mcp  (Mcp-Session-Id: abc123)          │
  │   {"id":3,"method":"tools/call",...}         │
  │─────────────────────────────────────────────→│
  │←── 200, Content-Type: text/event-stream ─────│  这个POST的响应变成流
  │←═ id: 1                                      │
  │   data: {"method":"notifications/progress"}══│  进度通知
  │←═ id: 2                                      │
  │   data: {"id":3,"result":{...}}          ════│  最终结果
  │        （服务端发完结果后关闭这条流）           │
```

关键点：这条 SSE 流**只服务于这一个请求**，是”请求级”的临时流，结果发完就关，不是全局长连接。

### 阶段 3（可选）：GET 长连接 —— 接收服务端主动消息

如果服务端需要**主动**发起消息（如 `sampling/createMessage` 请求、资源变更通知），客户端可以**额外**开一条 GET 流：

```
  │ GET /mcp                                     │
  │   Accept: text/event-stream                  │
  │   Mcp-Session-Id: abc123                     │
  │─────────────────────────────────────────────→│
  │←── 200, text/event-stream（长连接）───────────│
  │←═ data: {"method":"notifications/resources/  │
  │          list_changed"}                  ════│  服务端主动推送
```

这是旧协议那条 SSE 长连接的”等价物”——但在新协议里它是**可选的**：不需要服务端主动推送的场景可以完全不开。服务端不支持时直接返回 `405 Method Not Allowed` 也合法。

### 阶段 4：断线恢复（旧协议没有的能力）

服务端可以给 SSE 消息编号（`id:` 字段）。流意外断开后，客户端重连时带上：

```
  │ GET /mcp                                     │
  │   Mcp-Session-Id: abc123                     │
  │   Last-Event-ID: 2                           │  ← 从第2条之后续传
  │─────────────────────────────────────────────→│
  │←═ 重放 id > 2 的未送达消息 ═══════════════════│
```

### 阶段 5：会话终止

```
  │ DELETE /mcp                                  │
  │   Mcp-Session-Id: abc123                     │  客户端显式结束会话
  │─────────────────────────────────────────────→│
  │←── 200 OK ───────────────────────────────────│
```

服务端也可以单方面让会话过期——之后客户端带旧 sessionId 的请求会收到 `404`，客户端应重新 `initialize` 建新会话。

### 会话生命周期对比

```
HTTP+SSE：
  GET /sse ──→ endpoint事件(sessionId) ──→ POST initialize ──→ ... ──→ SSE断开即会话死亡
  会话生命 ＝ SSE连接生命（强绑定）

Streamable HTTP：
  POST initialize ──→ 响应头(Mcp-Session-Id) ──→ ... ──→ DELETE /mcp 或服务端过期
  会话生命 与 任何连接解耦（sessionId只是逻辑标识，可存Redis，实例间共享）
```

这个解耦是 Streamable HTTP 能做**无状态部署**的根本原因：会话状态可以外置到 Redis 等存储，任何一台实例收到带 `Mcp-Session-Id` 的请求都能处理，负载均衡随便路由；甚至对于不需要会话的简单工具服务，服务端可以干脆不发 `Mcp-Session-Id`，退化成纯粹的无状态 JSON API。

---

## 八、总结

| 问题 | HTTP+SSE 的答案 | Streamable HTTP 的答案 |
| --- | --- | --- |
| 响应怎么回来？ | 绕道：另一条全局 SSE 长连接 | 原路：POST 自己的响应体（JSON 或临时 SSE 流） |
| 长连接什么时候需要？ | 永远需要，是通信前提 | 仅当需要流式响应或服务端主动推送时 |
| 会话绑定在哪？ | 绑定在那条 SSE 连接上 | 绑定在逻辑 sessionId 上，与连接无关 |
| 断线后果？ | 会话灭失，全部重来 | 可 `Last-Event-ID` 续传，会话仍在 |

可以把 Streamable HTTP 理解为：**把”是否需要流”的决定权从协议层（必须先建流）下放到了每一个请求（按需变成流）**，从而同时兼容了”简单 REST 式调用”和”复杂流式交互”两种形态，这也是它取代 HTTP+SSE 成为 MCP 标准远程传输的原因。

抓包识别技巧：看到 `event: endpoint` + `?sessionId=` 就是旧版 HTTP+SSE 传输的握手特征；看到 `Mcp-Session-Id` 响应头则是 Streamable HTTP。目前很多服务端（包括官方 SDK）仍同时兼容两种传输以支持老客户端。