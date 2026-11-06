# LangGraph 会话存储入门（教学）

> 给谁看：看到技术方案里"`langgraph-checkpoint-redis`（AsyncRedisSaver，thread_id = 会话 ID，TTL 配置）"这行字不知道它在说什么的工程师。读完知道：会话存在哪、存了什么、怎么存取、我们方案的每个概念对应它的哪个机制。
> 关联：更完整的教学版（六个基本概念 + FAQ + 上手路径）在 design/4/实现细节/LangGraph落地设计-解读.md｜本目录为辅助理解材料，不进评审链路。

---

## 一、先把那行选型拆开

| 词 | 是什么 |
|---|---|
| `langgraph` | 引擎包本身（StateGraph / interrupt / 条件边…） |
| `langgraph-checkpoint-redis` | 引擎的"存 Redis"适配包（Redis 官方参与维护）。**不装它，引擎跑完就忘——没有任何持久化** |
| `AsyncRedisSaver` | 这个包里的保存器（Saver）类，异步版，配 FastAPI 的 async 栈；同步版叫 `RedisSaver` |
| `thread_id` | 会话的"档位号"。我们把会话 ID（`rp_9f3k2m`）传进去，一个 thread = 一次填报会话 |
| TTL 配置 | 检查点 Key 的过期时间，我们要 7 天（草稿保留期）。❗具体参数形态以 POC 实测为准 |

一句话：**引擎每执行完一个节点，自动调 Saver 把 State 快照写进 Redis，按 thread_id 归档；下次带同一个 thread_id 来，从最新快照原地继续。**

## 二、没有它 vs 有它

```python
# 没配 checkpointer：每次 invoke 都从 START 重跑，State 用完即弃
graph = builder.compile()
graph.invoke({"messages": [...]})
```

```python
# 配了 checkpointer：跨 invoke 记忆 + 自动存档
saver = AsyncRedisSaver("redis://...")
graph = builder.compile(checkpointer=saver)

cfg = {"configurable": {"thread_id": "rp_9f3k2m"}}
graph.invoke(user_msg_1, cfg)   # 跑 load_context→…→ask，每个节点后 saver 自动写 Redis
graph.invoke(user_msg_2, cfg)   # 不从头跑！从上次快照恢复 State，接着推进
```

**Spring 对照**：像给状态机配了个自动持久化层——相当于每执行完一个 handler 就自动 `save(session)`，但一行保存代码都不用写，而且存的不止数据还有进度（见下）。

## 三、到底存了什么（比"存个 JSON"多三点）

一个检查点 = **State 快照 + 框架运行元数据**：

| 内容 | 说明 | 游戏存档类比 |
|---|---|---|
| State（我们的黑板） | context / slots / agenda / messages / meta | 角色数据、背包、任务清单 |
| channel 版本 | 每个字段改到第几版 | 存档修订号（增量与时间旅行的依据） |
| 下一步（next） | 恢复后从哪个节点继续 | "你停在第三关" |
| 挂起信息（interrupt） | confirm 挂起时的载荷与位置 | 暂停菜单里待确认的选项 |

所以恢复的不只是"数据"，还有"**进度**"——这就是 interrupt 挂起后哪怕进程重启、换实例都不丢的原因。

## 四、Redis 里的样子（概念示意）

每次 invoke 产生一个新 `checkpoint_id`，同一会话的历史版本连成链：

```
checkpoint:{thread_id}                     ← 该会话的检查点链（含最新指向）
checkpoint_metadata:{thread_id}            ← 元数据
checkpoint_writes:{thread_id}:{ckpt_id}    ← 该步的写入明细
```

- 读最新 = `get_state(thread_id)`（只读快照，不执行图）；
- 时间旅行 = 指定历史 `checkpoint_id` 读旧版（排障利器：回放任意一步的现场）；
- ❗Key 前缀与结构随包版本可能变化——**POC 第一个动作：`redis-cli SCAN` 看真实 key，把实测形状截图归档**，本文档随之更新。

## 五、我们方案的每个概念对应它什么

| 方案概念（主文档/State设计.md） | LangGraph 机制 |
|---|---|
| 会话 ID `rp_9f3k2m` | `thread_id` |
| State 黑板（skill/slots/agenda/messages/meta） | 检查点的 values |
| "先存再推" | 每个 super-step 后 Saver 自动 put——零业务代码 |
| 断线重连 / 重进重放 | `get_state(thread_id)` 拿最新快照 + messages 按 seq 重放 |
| 确认单挂起等提交 | `interrupt()` 挂起也落检查点；`POST /submit` = `Command(resume)` 从该检查点续跑 |
| keep 7 天 / abandon 24h | TTL 配置 + 每日清理任务兜底（❗POC 确认参数行为） |
| `rp:active:{uid}` 活跃索引 | **我们自己的 Key，与框架无关**（按人找会话，重进复用入口） |
| 终态归档 MySQL、清 Redis | 应用层在 DONE 后做：读最新快照→写归档表→删该 thread 全部 Key |

## 六、一个会话在 Redis 的一生

建会话（thread 建立，v1 落盘）→ 每轮 invoke 追加检查点（v2、v3…历史链）→ confirm `interrupt()` 挂起（带挂起标记的检查点）→ submit `resume` → DONE → 应用层读最新快照写 MySQL 归档 → 删该 thread 全部 Key → **Redis 里这条会话消失，MySQL 里永久留存**。

## 七、FAQ

**Q：存这么多版本，Redis 不会涨爆吗？**
每轮一个检查点是历史链，但会话有界：TTL 到期自动清、终态即删、每日清理兜底；且交互有配额（单会话 ≤6 次 LLM）、text ≤500 字符——一个剧本 A 会话全程 ≈4KB。详见 State设计.md 设计决策第 9 条。

**Q：AsyncRedisSaver 和 RedisSaver 选哪个？**
FastAPI 全异步栈 → Async。POC 两个都试一下，确认 TTL 行为一致。

**Q：get_state 会执行图吗？**
不会，纯读快照。只有 invoke / resume 才驱动节点执行。

**Q：以后想换存储（比如回 MySQL）要改业务代码吗？**
不用。Saver 是 compile 的可插拔参数（本地调试可用 SqliteSaver、生产用 Redis），引擎和业务零改动。

**Q：这和我们自己写的"Redis 快照方案"（design/3 时期）差在哪？**
自研版要自己记得在每个状态转移后 `set(key, state_json)`、自己读档恢复、自己做版本；Checkpointer 把这三件事全部内建且每步必存（不会漏）、带进度与挂起语义、支持时间旅行。我们删掉的那 ~1.5 人天快照代码，就是它替我们写的部分。

## 八、POC 动手清单（半小时亲眼看到以上全部）

1. `pip install langgraph langgraph-checkpoint-redis`；写一个 3 节点小图 + AsyncRedisSaver；
2. invoke 两轮（同 thread_id）→ `redis-cli SCAN` 看 key 形状（截图归档，更新本文 §四）；
3. `get_state` 对比 invoke 前后；故意 kill 进程重进 → 亲眼确认恢复；
4. 配 TTL → 观察过期行为；不配 TTL → 用清理任务模拟"7 天清理"；
5. 在一个节点里 `interrupt()` → 看挂起检查点长什么样；`Command(resume)` 续跑。
