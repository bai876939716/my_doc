# State 设计说明（完整数据结构）

> 一句话：**State 是随会话流转的一块"黑板"——Checkpointer 落 Redis 的全部业务内容；本文给出完整结构、真实快照与每一条设计决策的理由。**
> 主文档：[../技术方案.md](../技术方案.md) §5.1 ｜ 类型定义与运行时使用：[LangGraph落地设计.md](./LangGraph落地设计.md)

---

## 一、完整结构总览（全字段表格）

**顶层 AgentState（9 个字段）**：

| 字段 | 类型 | 初始值（v1 建会话后） | 谁在何时写它 | 为什么存在于 State |
|---|---|---|---|---|
| `skill` | str | `"repair"` | 建会话写入，此后不变 | 恢复现场时定位技能包（schema/字典/话术/工具） |
| `session_id` | str | `"rp_9f3k2m"` | 建会话写入，此后不变 | = thread_id；归档、排查、日志关联免反查 |
| `context` | dict | 门店/报修人/日期/掩码电话 4 项 | `load_context` 工具带出一次写成 | 环境字段：确认单展示、提交映射都要用，但不进议程不追问 |
| `slots` | dict[str, SlotValue] | 7 格（phone 已带出，余 6 格空） | `update` 节点：点选定稿 / 抽取写入 / 钩子失效清空 | 业务事实的唯一载体（"槽位=已定稿事实"） |
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

## 二、类型定义

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

## 三、真实快照示例（v1 · 建会话后，Redis 检查点里的原样）

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

## 四、设计决策与理由

**1. 单一事实来源。** 字段填到哪了，真相只在 State——不在对话历史、不在前端、不在 LLM 的上下文里。换来的三件事：断线/退出恢复只需读 State 重放；喂给 extract 的是槽位快照而非聊天记录（上下文成本恒定）；审计时一处可查全部。

**2. 可序列化、最小完备——这是检查点机制的前提。** State 必须 整体转 JSON 落 Redis，因此：只放数据不放对象（照片存 media_id 引用，本体在 OSS）；只放"恢复现场需要"的字段，过程性中间产物（LLM 原始返回，抽取完即丢）不进——黑板永远最小，检查点体积可控。**往 State 加字段的门槛就一条：恢复时需不需要它。**

**3. 槽位只存"定稿事实"，候选住在消息里。** extract 产出的类别 Top3 不写 `slots.category`，只进下发的 `enum_card` 消息——槽位为空直到用户点选。理由：槽位是"已确认的事实"、卡片消息是"待确认的提议"，两者不混，防幻觉语义干净（AI 提名永远不入库）；重放时提议与事实都能还原。

**4. agenda 是派生数据，但显式存储。** 理论上 agenda 可由 schema 必填减 slots 已填实时算出；选择存下来，是因为它要承载**钩子的副作用**——重选类别把照片/描述"塞回议程"这种动作需要一个明确载体，实时计算无处落副作用。"问什么"由此从流程代码降维成数据（业务接入零流程代码的根基）。

**5. focus 是议程的影子。** 恒等于 `agenda[0]`，单独存是为了对外输出（`done.focus` / GET 返回）零计算；一致性由"agenda 变则 focus 同步变"的唯一写入点保证。

**6. 每个槽位值带 source 三值。** system_default / ai_filled / user_edited 不只是审计留痕：确认单上"AI 识别"角标、field_edit 埋点（AI 预填质量）、争议追溯（这个值是谁写的）都取自它。

**7. messages 只追加、永不进 Prompt。** append-only 保证重放/审计的不可篡改；"全量消息不进 Prompt、extract 只拿快照+最近几条"是成本恒定的根基——聊一百条，第一百次调用的输入和第一次几乎一样。

**8. 控制信息与业务数据分离。** branch/mode/fail_count/llm_calls/confirm_pending 这些"引擎怎么运转"的信号全部收在 meta，不与 slots 混放——业务规则读 slots，引擎逻辑读 meta，互不污染，后续技能包复用 slots 结构时也不用关心 meta。

**9. 消息持久化：State 放滑动窗口（最近 10 轮），全量每轮批量入库 MySQL。** State.messages 不承担全量留存——每轮结束时，本轮消息（用户 + 服务端卡片）**同步批量 upsert** 进 MySQL 消息表（一条 multi-row SQL），然后才裁剪窗口（保留最近 10 轮）。四条实现规则：① 写入顺序 = 先入库再改 State（含裁剪）——保证被裁掉的必已入库；② 消息表 (session_id, seq) 唯一键 + **upsert 后写覆盖**——崩溃在两写之间时，中断轮次重执行会重发同 seq 消息，upsert 自愈，不靠事务；③ 裁剪只在轮末做（本轮消息完整入窗，下轮才可能被裁）；④ seq 由 meta.seq_counter 生成（窗口裁剪后 messages.length 不可再用）。由此：State 恒有界（不依赖会话天然短的假设）、消息即时持久（abandon/崩溃的会话记录也在库）、审计零缺口。演进项（放量后）：改异步批量——轮末只投进程内写器（攒批 flush），配**裁剪同步门槛**（裁前必 flush）+ **复原/每日对账**（DB max(seq) 对比窗口最早 seq，落后就补）——窗口天然是缓冲区，落后上限=窗口容量，零丢失。
**10. 会话内容为什么放在 State 里（而不是单独存）。** 消息必须持久化、必须能随会话恢复——而 LangGraph 中唯一被框架自动原子持久化的容器就是 State。放进去白得四样东西：每步自动存档（零保存代码）、**消息与槽位变更同版本**（重放 / 时间旅行看到的永远是同一瞬间的完整现场，不会出现"消息在了、状态没跟上"的裂缝）、`add_messages` 按 ID 幂等合并、interrupt 挂起时对话现场随快照同存。备选方案均评估过：

| 备选 | 做法 | 为什么不选 |
|---|---|---|
| 消息单独存（Redis List `msg:{sid}`） | State 只存槽位，消息独立 Key | 两套存储无事务：崩溃窗口产生不一致；重放要自己对齐版本；seq 生成 / 清理 / 归档全部自写——等于把"先存再推"的活拿回一半 |
| 前端持有消息，服务端不存 | 断线靠前端本地恢复 | 违反"恢复不依赖前端本地"：换设备 / 清缓存重进无法还原，审计缺失 |
| 消息只落 MySQL，State 留最近 N 条 | 活跃会话重放查库 | 活跃路径多一跳 DB 依赖；未终态会话在 Redis 无完整重放材料；归档需合并去重——超长会话才值得 |
| 外挂 BaseStore 存消息 | 跨会话 KV | 为"按人长期记忆"设计，无版本链无 reducer，等于自管 |

边界：消息独立存储只在两种需求出现时才值得重开——千条级长会话（分页重放已预留）、消息需被第三方独立消费（质检 / 搜索流）。届时把 messages 拆到独立存储、State 只留游标即可，接口层（seq 分页）不用动。

> **决策 9 与 10 的关系**：消息的「窗口部分」在 State 里（与槽位同版本原子写，保重放一致性——决策 10 成立）；「全量持久」由每轮批量入库承担（幂等 upsert 自愈——决策 9）。上表否决的是「窗口外消息只存 Redis 独立 Key」的形态，不否决入库本身。
