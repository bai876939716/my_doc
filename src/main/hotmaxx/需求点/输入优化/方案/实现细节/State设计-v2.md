# State 设计（v2 · 差异修订）

> v1 已交付留档（[State设计.md](State设计.md)），九字段结构继续有效。本文只列与实现的偏差（依据《[变更记录/实现变更记录.md](变更记录/实现变更记录.md)》与《[图节点设计-v2.md](图节点设计-v2.md)》）。

---

## 与 v1 的差异（四点）

| # | 项 | v1 | v2（当前实现） |
|---|---|---|---|
| 1 | **meta.confirm_pending** | 无此键（确认靠 interrupt 挂起） | **确认等待的数据态**：confirm 节点置位；ask / fallback 清位；submit 成功或校验失败清位；建单失败保持（可重试） |
| 2 | **messages** | 最近 10 轮滑动窗口，全量在 MySQL | **全量、无裁剪**；每条带 `role`（user/server）——用户手势经 intake 落 role=user 档（恒为本轮第 1 条），GET 重放与审计的真值 |
| 3 | **seq** | 预留块 + 挂起载荷合并（补丁方案） | **计数器生成、全会话连续单调**（无预留空洞；11-12 拔根时删除的补丁） |
| 4 | **载体** | Redis 检查点（快照即"Redis 里的原样"） | **PG**（checkpoints 三表；`CHECKPOINT_MODE=memory\|postgres`） |

## 其他新增 Meta 键（随 11-12 行为增强）

- `pending_update`：update 无值时的轻量挂起（下轮裸值消解）；
- `submit_error`：建单失败标记（保持确认态可重试）；
- `order` / `order_key`：幂等键（= 会话 ID）。

> State 完整结构与设计理由仍以 v1 §一~§三为准；场景级每版快照见 [../diagrams/场景演示.html](../diagrams/场景演示.html)（注意其字典/照片话术为旧口径，流程骨架仍准）。
