# State 设计说明（完整数据结构）

> State 是随会话流转的一块"黑板"——Checkpointer 落 Redis 的全部业务内容。本文给出**真实快照**与**完整结构**；每条设计的取舍理由见《[ext/关键决策问答.md](ext/决策.md)》"State 设计决策"专题。主文档：[../技术方案.md](../技术方案.md) §4.1

---

## 一、真实快照示例（v1 · 建会话后，Redis 检查点里的原样）

```json
{
  "skill": "repair",
  "session_id": "rp_9f3k2m",
  "context": { "store": "11522 安徽商之都OK店", "reporter": "白云翔", "date": "2026-11-05", "phone_masked": "138****1234" },
  "slots": {
    "category":        { "value": null, "source": "system_default", "updated_at": "-" },
    "detail":          { "value": null, "source": "system_default", "updated_at": "-" },
    "photos":          { "value": [],   "source": "system_default", "updated_at": "-" },
    "expect_visit":    { "value": null, "source": "system_default", "updated_at": "-" },
    "impact_business": { "value": null, "source": "system_default", "updated_at": "-" },
    "phone":           { "value": "13800001234", "source": "system_default", "updated_at": "2026-11-05T09:41:03+08:00" },
    "expect_process":  { "value": null, "source": "system_default", "updated_at": "-" }
  },
  "agenda": ["category", "detail", "photos", "expect_visit", "impact_business", "phone"],
  "focus": "category",
  "messages": [
    { "seq": 1, "role": "server", "type": "status_bar", "data": { "store": "11522 安徽商之都OK店", "reporter": "白云翔", "phone_masked": "138****1234" } },
    { "seq": 2, "role": "server", "type": "text", "data": { "content": "白云翔你好！直接说哪儿坏了就行…" } },
    { "seq": 3, "role": "server", "type": "quick_cards", "data": { "cards": [ { "id": "c_cool", "icon": "❄️", "title": "冷柜/空调不制冷" } ] } }
  ],
  "meta": { "branch": "a", "mode": "normal", "fail_count": 0, "llm_calls": 0, "confirm_pending": false, "seq_counter": 3, "exit_status": null, "order": null },
  "rows": []
}
```

此后每一轮的变化只发生在四处：**slots 某格被写 / agenda 划项或回填 / messages 追加 / meta 计数**。逐版全量值见《[场景演示.html](../diagrams/场景演示.html)》（每步可点开完整 JSON）。

---

## 二、完整结构总览（全字段表格）

**顶层 AgentState（9 个字段）**：

| 字段 | 类型 | 初始值（v1 建会话后） | 谁在何时写它 | 为什么存在于 State |
|---|---|---|---|---|
| `skill` | str | `"repair"` | 建会话写入，此后不变 | 恢复现场时定位技能包（schema/字典/话术/工具） |
| `session_id` | str | `"rp_9f3k2m"` | 建会话写入，此后不变 | = thread_id；归档、排查、日志关联免反查 |
| `context` | dict | 门店/报修人/日期/掩码电话 4 项 | `load_context` 工具带出一次写成 | 环境字段：确认单展示、提交映射都要用，但不进议程不追问 |
| `slots` | dict[str, SlotValue] | 7 格（phone 已带出，余 6 格空） | `update` 节点：点选定稿 / **抽取直填（无 MustPick 的枚举槽，说到的即入槽 ai_filled）** / 钩子失效清空 | 业务事实的唯一载体（"槽位=已定稿事实"） |
| `agenda` | list[str] | 6 项必填槽位名（schema 顺序） | 建（schema 生成）/ 答一划一 / 钩子失效回填 | "问什么"的数据化——引擎按它推进，业务接入零流程代码的根基 |
| `focus` | str \| null | `"category"` | agenda 变则同步变（唯一写入点） | = agenda 首项的影子；对外输出（done.focus / GET）零计算 |
| `messages` | list[MessageEntry] | 首屏 3 条（status_bar/text/quick_cards） | 每轮追加（用户 + 服务端卡片）；**滑动窗口：只保留最近 10 轮**，更早消息轮末批量入库后裁剪 | 断线重放的尾巴材料、extract 的近期上下文、候选提议的存放地；**不进 Prompt**；全量在 MySQL 消息表 |
| `meta` | Meta | 8 键初值（见下表） | 各节点按职责写（llm_calls 在 extract、fail_count 在 route…） | 控制信息与业务数据分离：引擎读 meta，业务读 slots |
| `rows` | list[dict] | `[]` | （后续技能包的子表行） | 结构预留：报修恒为空，报销费用明细用，避免改结构 |

**嵌套类型 SlotValue（一个槽位一格，共 3 个字段）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `value` | str \| list[str] \| null | 文本/枚举/日期 = str；photos = media_id 列表（附件本体在 OSS） |
| `source` | str | `system_default`（带出）/ `ai_filled`（AI 预填）/ `user_edited`（用户点选或修改）——审计：值是谁写的 |
| `updated_at` | str | ISO8601，最后写入时间 |

**嵌套类型 MessageEntry（一条消息，共 4 个字段）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `seq` | int | 全会话单调递增；兼作消息 ID（add_messages 幂等合并依据；断线重放按它补齐） |
| `role` | str | `user`（用户输入）/ `server`（下发的卡片与话术） |
| `type` | str | 与接口文档 §2.2 的 type 一一对应（text/enum_card/…） |
| `data` | dict | 该 type 的负载（text.content / enum_card.options / …） |

**嵌套类型 Meta（8 个键）**：

| 键 | 类型 | 初始值 | 谁写 | 用途 |
|---|---|---|---|---|
| `branch` | str | `"a"` | 首个用户动作时定（打字=a / 点卡=b） | 路径埋点、B 路径 DefaultFill 判定 |
| `mode` | str | `"normal"` | route 降级时切 `"fallback"` | fallback 后打字不再调 LLM |
| `fail_count` | int | 0 | route 拉回/识别失败时 +1，成功提供后清零 | ≥2~3 触发降级判定 |
| `llm_calls` | int | 0 | extract 每次调用 +1 | 单会话 ≤6 配额依据；成本对账 |
| `confirm_pending` | bool | false | confirm 挂起置 true，resume 后 false | 标记 interrupt 状态（挂起也存档） |
| `seq_counter` | int | 3（首屏消息数） | 每条消息 +1（push 时） | 消息序号发生器——窗口裁剪后 `messages.length` 不可再当 seq 用 |
| `exit_status` | str \| null | null | exit 接口写 keep/abandon | 退出语义与留存策略 |
| `order` | dict \| null | null | submit 成功写 `{order_no, status}` | 终态单号，归档用 |
| | | | | （`branch`/`mode`/`fail_count`/`llm_calls`/`confirm_pending` 五键为引擎运转信号；`seq_counter` 为消息序号发生器） |

> 每一轮的变化只会落在四处：**slots 写格 · agenda 划项/回填 · messages 追加 · meta 计数**——此外没有任何隐式变化（场景验证见 [../diagrams/场景演示.html](../diagrams/场景演示.html)）。

---

## 三、类型定义

```python
class SlotValue(TypedDict):
    value: str | list[str] | None   # 文本/枚举/日期 = str；photos = media_id 列表
    source: str                     # system_default | ai_filled | user_edited（审计：值是谁写的）
    updated_at: str                 # ISO8601

class MessageEntry(TypedDict):
    seq: int                        # 全会话单调递增；兼作消息 ID（add_messages 幂等合并）
    role: str                       # user | server
    type: str                       # 与接口文档 §2.2 的 type 一一对应
    data: dict                      # text.content / enum_card.options / …

class Meta(TypedDict):
    branch: str             # a=描述路径 | b=卡片路径
    mode: str               # normal | fallback（降级后打字不再调 LLM）
    fail_count: int         # 连续拉回/识别失败计数（≥2~3 触发 fallback）
    llm_calls: int          # 本会话 LLM 次数（单会话 ≤6 配额依据）
    confirm_pending: bool   # 当前是否挂起在 confirm（interrupt 中）
    seq_counter: int    # 消息序号发生器（窗口裁剪后 length 不可用作 seq）
    exit_status: str | None # keep | abandon | None
    order: dict | None      # DONE 后 {order_no, status}

class AgentState(TypedDict):
    skill: str                      # "repair"（恢复时定位技能包）
    session_id: str                 # rp_xxx = thread_id（冗余存一份：归档/排查免反查）
    context: dict                   # 带出字段 {store, reporter, date, phone_masked}
    slots: dict[str, SlotValue]     # 7 个槽位（定义见技能包实例 §2）
    agenda: list[str]               # 待问必填槽位名（schema 顺序）
    focus: str | None               # 当前焦点 = agenda 首项（对外渲染 done.focus）
    messages: Annotated[list[MessageEntry], add_messages]   # append-only · 滑动窗口 10 轮（裁剪前必已入库）
    meta: Meta
    rows: list[dict]                # 子表行（后续技能包预留，报修恒为 []）
```
