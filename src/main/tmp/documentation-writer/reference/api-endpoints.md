# 参考:API 端点

Base 路径:`/agent/repair/v1`(来源:`app/api/routes.py:21`)。同步 JSON,全部接口要求 `X-Uid` 请求头(缺失 → 401,业务码 40101;由网关/转发层注入身份,服务端只验有无)。

错误响应统一形状:`{"detail": {"code": <业务码>, "message": <人话>}}`,HTTP 状态 = 业务码整除 100(来源:`routes.py` `_err`)。

## 探针(无身份要求)

| 端点 | 说明 |
|---|---|
| `GET /healthz` | liveness:进程活着即 200;返回 `{"status":"ok","skills":[...]}`(来源:`app/main.py:167`) |
| `GET /readyz` | readiness:postgres 模式探活 PG,首次就绪时建表;memory 模式恒就绪。返回 `mode`/`llm_model`/`real_order_enabled`/`production_ready`(当前恒 false)等(来源:`app/main.py:184`) |

## 业务五接口

### ① POST /sessions —— 建会话 / 恢复

请求体:`{"store_no": "<门店编号,必填,无空白>", "restart": false}`
`restart=true`:单请求内在入口锁中完成「放弃旧会话 + 另起新单」。

响应:`{session_id, is_resumed, context, messages[]}`。`is_resumed=true` 时 `messages` 含 `role=user` 的历史请求回显,前端据此重建用户气泡。

草稿按 `(uid, store_no)` 键存活跃索引——一人多店,跨店各开各会话(来源:`app/api/sessions.py`)。

### ② POST /sessions/{sid}/messages —— 发消息(核心轮次)

请求体 `IncomingMsg`(来源:`routes.py:27-73`):

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | 枚举 | `text` / `card` / `enum_pick` / `seg_pick` / `upload_done` / `phone` |
| `content` | str? | `text` 必填非空 |
| `card_id` | str? | `card` 必填,须在技能包快捷卡片白名单 |
| `field` + `value` | str? | 点选必填;`field` 须为技能包声明的槽位,`value` 须在字典白名单 |
| `attachments` | list? | `upload_done` 必填;元素 `{name,url}`,不可重复,≤ `PHOTOS_MAX` |
| `use_default` + `phone` | | `phone` 轮:默认号与自定义号互斥;自定义须过识别器(11 位手机号) |

响应 `RoundResponse`:`{state, focus, messages[]}`——只含本轮新增消息(不含用户刚发的那条,前端已自画)。

`state` 取值(来源:`routes.py` `_api_state`):`collecting`(收集中)/ `confirm`(确认卡待确认)/ `done`(已出单)/ `fallback`(降级卡片向导)。已出单的会话再发消息 → 42201「本单已提交,不能继续修改」。

### ③ GET/POST /sessions/{sid} —— 会话内刷新 / 超时对齐

GET 与 POST 同义(公网网关按「地址+平台」索引,App 查会话统一走 POST)。响应:`{state, focus, collected, context, messages[]}`。

`collected` 为已收集字段;声明了 display 的槽位只出显示形态——手机号在此口径下是掩码,明文只存在于服务端 State 与明道云建单(来源:`routes.py:251-259`、`app/core/redact.py`)。`messages` 含用户回显(全量重放)。

### ④ POST /sessions/{sid}/submit —— 提交出单

仅 `confirm` 态可提交(否则 40901)。接口注入内部手势 `submit` 直达建单。幂等:已出单的会话重复 submit 原样返回同一单号,并补做崩溃时缺失的归档(来源:`routes.py:274-279`)。

响应:`{order_no, status}`。`REAL_ORDER_ENABLED=false` 时单号为 `MOCK-` 前缀。建单链路异常 → 50201。

### ⑤ POST /sessions/{sid}/exit —— 主动退出

请求体:`{"mode": "keep" | "abandon"}`

- `keep`:保留草稿,响应含 `draft_expired_at`(now + `ACTIVE_TTL_DAYS` 天)
- `abandon`:归档 → 清活跃索引 → 销毁检查点,一次在入口锁内完成
- 幂等:会话已不存在时直接回 `{"result":"ok"}`

## 错误码表

| 业务码 | HTTP | 场景 |
|---|---|---|
| 40101 | 401 | 缺 `X-Uid` 且未开匿名 |
| 40401 | 404 | 会话不存在或已过期;**非本人会话同响**(防探测) |
| 40901 | 409 | 非确认态调用 submit |
| 42201 | 422 | 校验失败:脏输入、附件超限、已提交再改、mode 非法等 |
| 50201 | 502 | submit 建单链路异常 |
| 50301 | 503 | 全局未处理异常兜底(统一 `{code,message}` 形状,来源:`app/main.py:173`) |

## 并发语义

同一 `sid` 的图操作由进程内锁 `session_guard` 串行;同一 `(uid, store_no)` 的建会话/恢复/放弃由 `entry_guard` 串行(来源:`app/api/sessions.py`)。锁仅在单进程内有效——**多副本部署会串会话**,扩容前需换 PG advisory lock。
