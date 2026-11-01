# SSE 与状态保存 · 详细解读

> 配套：[设备报修-AI填报Agent技术实现方案.md](../设备报修-AI填报Agent技术实现方案.md) §7（接口）、§6（会话与草稿）。本文把这两个"评审必问"的实现细节拆到代码级。

---

## 一、SSE 如何实现

### 1.1 先弄清 SSE 是什么

SSE（Server-Sent Events）就是**一个不会主动断开的 HTTP 响应**：服务端把想要推送的消息一条条写进响应体，前端边收边处理。协议极简，本质是 `Content-Type: text/event-stream` 的长连接 + 固定文本格式：

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
X-Accel-Buffering: no          ← 告诉 nginx 别缓冲，收到就转发

event: typing                   ← 消息类型（可选）
data: {"msg_id":"m_01"}         ← 载荷（一行或多行 data）

event: text
data: {"content":"报修类别已锁定"}

event: quick_cards
data: {"cards":[...]}
```

每条消息以**空行**分隔；`event` 是自定义类型，直接对应方案 §7 消息协议的 `type` 字段（text / quick_cards / enum_card / confirm_card…），前端按 type 分发渲染——**这就是"消息协议与原型组件一一对应"的落地方式**。

### 1.2 服务端实现（FastAPI）

核心是 `async generator`：函数里 `yield` 一条，连接就推一条。用 `sse-starlette` 库（自动处理格式、心跳、断连）：

```python
from sse_starlette import EventSourceResponse

@app.post("/api/sessions/{sid}/messages")
async def send_message(sid: str, msg: MessageIn, request: Request):
    async def gen():
        # ① 立刻推 typing，前端马上出现"输入中"动画（覆盖 LLM 等待）
        yield {"event": "typing", "data": "{}"}
        # ② 状态机处理：转移状态 + 产出下一步消息序列
        outputs = await engine.handle(sid, msg)     # 见第二章
        for m in outputs:                            # 逐条推送
            if await request.is_disconnected():      # 用户已断开则停止
                break
            yield {"event": m.type, "data": m.json()}
        # ③ 收尾：本轮交互结束标记（前端关 loading）
        yield {"event": "done", "data": "{}"}
    return EventSourceResponse(gen(), ping=15)       # ping=15s 心跳保活
```

三条实践要点：

- **typing 先行**：不等 LLM/工具返回，请求一进来就推 typing，用户感知零延迟
- **心跳**：`ping=15` 每 15 秒发一行注释帧，防止 nginx / 负载均衡把"安静"的连接当超时掐掉
- **断连检查**：每次 yield 前查 `request.is_disconnected()`，用户杀 App 后立即停止生成，不白烧 token

### 1.3 前端实现：为什么不能用 EventSource，用 fetch 流式读取

浏览器原生 `EventSource` **只支持 GET**，而用户消息是 `POST /messages`。两种解法：

| 方案 | 做法 | 取舍 |
|---|---|---|
| A. fetch + ReadableStream（**采用**） | `fetch POST` 后用 `response.body.getReader()` 手动读流、按空行切消息 | 一条连接搞定请求+流式响应，语义清晰 |
| B. EventSource + GET 查询参数 | 消息内容放 URL 走 GET，EventSource 自动重连 | URL 长度/中文编码受限，语义别扭 |

方案 A 前端核心代码：

```javascript
async function sendMessage(sid, msg) {
  const resp = await fetch(`/api/sessions/${sid}/messages`, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(msg),
  });
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    buf += decoder.decode(value, {stream: true});
    const frames = buf.split("\n\n");           // 空行分隔消息帧
    buf = frames.pop();                          // 半截留到下一轮
    frames.forEach(f => render(parseFrame(f)));  // 按 event 分发渲染
  }
}
```

### 1.4 断线与恢复

SSE 断线（切后台、弱网）**不需要补消息**，因为我们存了状态：

1. 前端监听流结束/报错 → 调 `GET /api/sessions/{sid}`
2. 服务端从 Redis 读会话快照，返回当前状态 + 该步骤的完整 UI 消息（不是增量），前端整屏重放
3. 用户从断点继续，不丢任何已答内容

> 设计取舍：不做消息级补发（记录已推消息序号重放）——状态快照重放更简单，且天然覆盖"换手机继续填"的场景。

### 1.5 部署注意（上线前检查清单）

- nginx 位置：`proxy_buffering off;` 或依赖响应头 `X-Accel-Buffering: no`，否则消息会被攒成一坨一次性吐出
- 网关超时调到 ≥120s（一次 LLM 调用 + 推流总时长）
- 单实例 SSE 连接数有限（uvicorn worker 数 × 每进程连接上限），Agent 服务无状态可横向扩，连接属于短生命周期（一次交互一条，推完即关），不存在长连接堆积

---

## 二、状态的保存怎么实现

### 2.1 保存的到底是什么

一个会话在 Redis 里就是一个 JSON（Key：`rp:{session_id}`），内容=**状态机的全部现场**：

```json
{
  "session_id": "s_9f2c",
  "user_id": "u_1001",
  "store_no": "SH0021",
  "state": "PHOTO",
  "branch": "B",
  "ver": 23,
  "collected_fields": {
    "category": "收银设备 - 收银机/扫码枪故障",
    "detail": {"表现": "…", "原因": "…", "后果": "…"},
    "photos": ["m_001", "m_002"],
    "contact": {"name": "王芳", "phone": "+86139****0000"},
    "brought": {"region": "华东大区", "store": "…", "addr": "…", "hours": "…"}
  },
  "updated_at": "2026-11-01T10:00:00Z"
}
```

有了这份快照，任何时候都能回答三个问题：**走到哪了（state）、已经采到什么（collected_fields）、下一步该干什么（转移表查 state）**。

### 2.2 保存时机：跟着状态变更走，不跟"退出"走

```python
async def handle(sid: str, msg: MessageIn):
    sess = await load_session(sid)              # ① Redis 读快照
    event = classify(msg)                       # ② 消息→事件（card/text/pick/upload…）
    nxt = TRANSITIONS[sess.state].get(event)    # ③ 查转移表（纯内存，微秒级）
    if not nxt:
        return [Msg("text", "请点击上方选项继续")]
    outputs = await HANDLERS[nxt](sess, msg)    # ④ 执行状态处理器（可能调 LLM/MCP）
    sess.state = nxt
    await save_session(sess)                    # ⑤ ★ 每次状态变更后立刻落 Redis
    return outputs                              # ⑥ 交给 SSE 逐条推送
```

第 ⑤ 步是关键：**保存发生在每个状态变更之后、推送之前**。所以杀 App、闪退、断网、任何形式的"中途退出"都不需要被感知——用户最后答完的那个字段一定已经写进 Redis 了。

```python
async def save_session(sess):
    sess.ver += 1                               # 版本号：乐观锁
    sess.updated_at = now_iso()
    pipe = redis.pipeline()
    pipe.set(f"rp:{sess.session_id}", json.dumps(sess))
    pipe.expire(f"rp:{sess.session_id}", 7*86400)  # TTL 滑动续期：活跃会话永不过期
    # 顺手续用户当日配额计数（见成本控制 §9.1）
    await pipe.execute()
```

三个细节：

- **TTL 滑动续期**：每次保存都重置 7 天倒计时——"7 天"指的是最后一次操作后 7 天，不是会话创建后 7 天
- **版本号 ver（乐观锁）**：店员手机 + 平板同时开着同一会话时，后写入的版本号低会被拒绝，防止旧屏幕覆盖新数据
- **MySQL 归档**：提交成功（DONE）后异步写一份到 MySQL `session_archive` 表用于分析，Redis 里的在 TTL 后自然消失

### 2.3 恢复：断点续填的完整链路

```
用户打开 AI 入口
  → 前端读本地 storage 里有没有 session_id
    → 无：POST /api/sessions 新建（INIT，带出 9 字段）
    → 有：GET /api/sessions/{sid}
        → Redis 命中：返回 state=PHOTO + 快照 → 前端整屏重放到照片步骤，已答内容回显
        → Redis 未命中（过期>7天）：提示"上次会话已过期"，引导新建
```

前端"重放"就是按状态直接渲染该步骤的 UI（照片指引卡 + 已传缩略图），**不回放历史聊天记录**——用户要的是继续办事，不是看聊天记录，这样实现也最简单。

### 2.4 为什么这套设计经得起追问

| 追问 | 回答 |
|---|---|
| Redis 挂了怎么办 | 会话退化为进程内存（牺牲断点续填），提交流程不受影响（§9 降级表） |
| 会不会保存失败导致状态错乱 | save 在 push 之前：保存失败直接抛错、本轮消息不推，用户重试，不存在"看到了回复但没存上" |
| 双端并发写 | ver 乐观锁，旧版本写入被拒 |
| 和老表单草稿冲突 | Key 空间不同（`rp:` 前缀 vs 老表单自己的草稿），物理隔离互不覆盖 |
| 7 天够吗 | 由埋点 `session_end - session_start` 分布决定，配置化可调 |

---

## 三、一次交互的完整时序（串起 SSE + 状态保存）

```
店员点卡片
  │ POST /messages {card_click}
  ▼
Agent 服务 ── Redis 读快照(state=INIT) ──► 转移表查得 → B_LOCK
  │                                        │
  │                              处理器：锁定类别、组装消息
  │                                        │
  │◄── save_session(state=B_LOCK, ver+1) ──┘   ★ 先保存
  ▼
SSE 推送：typing → text(类别已锁定) → done
  ▼
前端逐条渲染，本条连接关闭
```

全程零 LLM 调用、一次 Redis 读 + 一次 Redis 写，P95 延迟可控制在 100ms 内。

---

## 附：评审快问快答卡

| 问 | 一句话答 |
|---|---|
| SSE 和 WebSocket 为啥选前者 | 消息是单向下推（用户消息走 POST），SSE 半个复杂度够用且过网关最省事 |
| EventSource 为啥不用 | 只支持 GET，POST 场景用 fetch 流式读取 |
| 断线消息丢吗 | 不补消息，GET 会话快照整屏重放，状态在 Redis |
| 状态存哪 | Redis 单 Key JSON，TTL 7 天滑动续期，ver 乐观锁 |
| 什么时候保存 | 每次状态变更后、推送前——退出动作无需感知 |
| Redis 挂了 | 降级内存会话，报修不受影响，只丢断点续填 |
