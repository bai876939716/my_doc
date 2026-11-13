# LangGraph 落地设计

> 一句话：**通用采集图是一张固定的 StateGraph，技能包只提供"数据"（字段 schema / 字典 / 话术 / 工具 / 钩子）；Checkpointer 管存档恢复，interrupt() 管确认单，LLM 只在 extract 节点出现。**
> 主文档：[../技术方案.md](../技术方案.md) §6

---

## 一、概念地图

LangGraph 六个核心概念（StateGraph / 节点 / 条件边 / Checkpointer / interrupt / BaseStore）的入门讲解、Spring 对照与白话例子，见 [LangGraph落地设计-解读.md](LangGraph落地设计-解读.md)。

依赖三件：`langgraph`（引擎）、`langgraph-checkpoint-redis`（Redis 官方维护的 Saver）、`langchain`（仅用其 LLM 封装）。

---

## 二、State 形状（图里流转什么）

```python
class AgentState(TypedDict):
    skill: str                    # 技能包名 repair / expense
    context: dict                 # 带出字段（门店/人员/申请编号…）
    slots: dict[str, SlotValue]   # 槽位值 {value, source, updated_at}
    agenda: list[str]             # 待问槽位队列（由 schema 必填项生成，动态收敛）
    rows: list[dict]              # 子表行草稿（报销用，报修为空）
    meta: dict                    # branch / fallback_count / exit_status / ver 信息
    messages: Annotated[list, add_messages]   # 对话消息（reducer 追加）
```

要点：**"问什么"不是流程，是数据**——`agenda` 由技能包 schema 的必填槽位算出，答一个划掉一个，随时改字段不重排。

## 三、通用图：节点与边

```
START ──> load_context ──> extract ──> update ──> route ──┬─> ask ──> END(本轮结束)
                                ^                    ├─> confirm(interrupt) ─┬─> submit ──> DONE
                                └── 打字消息(下一轮)   └─> fallback ──> END    └─ resume(改字段) ─> extract
```

| 节点 | 输入→输出 | 说明 |
|---|---|---|
| `load_context` | context 空 → 调技能包 context 工具填 | 建会话第一轮走；下发状态条 + 高频卡片 |
| `extract` | 新打字消息 → 槽位增量/意图/子表行草稿 | **全流程唯一 LLM 调用点**（点选/卡片消息跳过此节点） |
| `update` | 增量 → 过闸（白名单/树匹配/金额）写 slots | 不过闸的丢弃并解释；钩子联动失效在此触发 |
| `route` | 看 agenda + 意图 | 意图 exit→EXITED；边界/连续失败→fallback；必填齐→confirm；否则 ask |
| `ask` | agenda 首项 → 话术模板 + 槽位类型映射卡片 | 0 LLM；每轮结束落 END，等下一条消息 |
| `confirm` | 生成确认单消息 → `interrupt(payload)` 挂起 | submit 接口 = `Command(resume={"action":"submit"})`；改字段 = resume 带新值回 extract |
| `submit` | 调技能包提交工具（order_key 幂等） | 成功→DONE（清活跃索引、归档 MySQL）；失败→报错消息回 confirm |
| `fallback` | 全量字典/树搜索卡片向导 | 0 LLM；可切老表单 |

**每轮 = 一次图执行**：`messages` 接口收到用户消息 → `graph.ainvoke({"messages":[...]}, config={"thread_id": session_id})` → 图跑到 ask 或 confirm 挂起 → 本轮结束。跨轮状态全靠 Checkpointer，**不用自己写"先存再推"**。

## 四、与六接口的对接（接口零改动的原因）

| 接口 | 图侧动作 |
|---|---|
| `POST /sessions` | 查 `rp:active:{uid}` 复用或新建 thread；首轮跑 `load_context`；is_resumed 时 `get_state` 重放消息 |
| `POST /messages` | `ainvoke` 新消息（打字→经 extract；点选卡片→直接进 update） |
| `POST /upload` | media_ids 写入 slots（photos/attachments）→ 走 update 校验 |
| `GET /{id}` | `get_state` + 消息重放（SSE 断线重连/刷新） |
| `POST /submit` | `Command(resume={"action":"submit"})` 恢复 confirm 挂起 → submit 节点 |
| `POST /exit` | mode=keep：状态留在检查点（7 天）；abandon：标记废弃（24h 清理） |

SSE 流：`graph.astream_events(...)` → 引擎层把节点产出翻译成卡片消息（`status_bar`/`enum_card`/`tree_card`/`confirm_card`...）推流。**卡片协议是产品资产，保留自有协议，不用框架的事件格式对前端**。

## 五、Checkpointer 配置与 Key 布局

| 项 | 设计 |
|---|---|
| Saver | `AsyncRedisSaver`（langgraph-checkpoint-redis，Redis 官方维护） |
| 存什么 | **整个 State 快照**：槽位 slots + 议程 agenda + 上下文 context + **全部对话消息 messages**（用户输入与服务端卡片消息都追加进 messages 通道——`GET /{id}` 的消息重放、断线重连还原全靠它；附件只存 media_id 引用） |
| thread | `thread_id = session_id`（`rp_*` / `ex_*` 前缀） |
| TTL | Saver 的 TTL 配置 + **每日清理任务兜底**（扫描超 7 天检查点删除）❗POC 验证项：确认 TTL 字段行为与滑动续期实现 |
| 保留 Key | `rp:active:{uid}`（业务前缀，活跃索引）、`quota:{uid}:{date}` 不变 |
| 归档 | DONE 后把终态 State（槽位+消息+轨迹）写 MySQL，删检查点 |

## 六、跨会话记忆：收款人四件套（报销，工具化）

- **实现方式**：两个记忆工具 `recall_payee(uid)` / `save_payee(uid, 四件套)`，走 ToolProvider 与业务工具同一路径；存储为 Redis Key `payee:{uid}`（最后一次为准）。
- **为什么工具化而不是引擎直读写**（如框架的 BaseStore）：与其他工具同路径 = 同样的可测试、可限流、可审计；未来工具层独立服务化 / MCP 化 / 多 Agent 共享时，记忆天然跟着走，引擎不背包。
- 读取时机：进入收款人 `subgroup` 槽位时经工具查询，命中 → `group_card` 预填 + "用上次的/改" 二选；未命中 → 逐项问。
- 写入时机：确认单提交成功后经工具写回。
- 跨业务可复用：报修的"常用联系人"同理。

## 七、迁移路径（现有 agent-service-center 骨架）

| 现状 | 动作 |
|---|---|
| `app/core/models`（Pydantic 槽位/消息模型） | **保留**，State 直接引用 |
| `app/core/engine`（状态机骨架，尚未实现） | **重写为 graph builder**（本篇 §三） |
| `app/core/store`（内存版预留） | **删除**，换 AsyncRedisSaver + BaseStore |
| `app/core/llm`（LLMGateway + Mock） | **保留**，extract 节点内调用 |
| `app/skills/repair` | 结构不变，`states/` 换成 schema + 钩子声明 |
| `app/api` | **零改动**（接口/路由/URL 不变） |
| 新增 `app/skills/expense` | 按《[技能包契约与报销接入.md](技能包契约与报销接入.md)》 |

## 八、测试策略

| 层 | 做法 |
|---|---|
| 节点单测 | extract 的闸门逻辑 / update 的联动失效 / route 的议程收敛——pytest 纯函数测试，0 LLM（MockLLM） |
| 图级测试 | 剧本回放：FA 剧本 A/B + 报销剧本逐条消息 invoke，断言卡片序列与槽位终值 |
| 断点测试 | 中途 `get_state` → 新进程 invoke 续跑（验证 Checkpointer 真恢复） |
| 时间旅行 | Checkpointer 自带历史快照，回归时可直接 diff 两次执行轨迹 |

## 九、POC 清单（11.5~11.6，三个必须亲手验证）

1. **Checkpointer TTL**：AsyncRedisSaver 的 TTL 配置行为 + 7 天滑动续期实现方式（配置 or 清理任务）。
2. **interrupt ↔ SSE**：confirm 挂起后 SSE 流正常收尾；`POST /submit` 恢复后 `result` 消息正常推出。
3. **astream_events**：节点事件 → 卡片消息映射的性能与稳定性（长连接下无泄漏）。

任一项不成立 → 降级方案：TTL 不行则清理任务全托管；interrupt 不行则 confirm 退化为普通节点（自管提交状态，成本 +0.5 人天）。
