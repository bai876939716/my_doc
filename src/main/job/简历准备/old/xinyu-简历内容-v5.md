# 心屿 AI 虚拟角色社交 App

## 技术栈

| 层级 | 技术 |
|------|------|
| **AI 层** | LangGraph、LangChain、MCP、Pydantic、Qwen3-Max / DeepSeek-V4-Flash / MiniMax-M2.7 |
| **后端** | Spring Boot、MyBatis-Plus、RocketMQ、Redisson 分布式锁、XXL-Job |
| **存储** | PostgreSQL、PolarDB 向量引擎（Memory API）、Redis |
| **基础设施** | 阿里云 AgentRun（Serverless）、Nacos 配置中心、Hermes 长连接推送 |

## 项目介绍

心屿是以 AI 虚拟角色为核心的情感陪伴 App，提供三种体验：日常短对话、角色主动发布的个性化 Feed 动态、以及模拟"线下见面"的沉浸式 AI 剧本互动（星梦）。

## 架构总体思路

系统采用 **Java 微服务 + Python AI Agent 双层分工**架构：

- **Java 层**：承担业务逻辑、状态管理与数据持久化
- **Python 层**：LangGraph 状态图 + 5 个 Agent，承担 LLM 推理与内容生成
- 两层通过 **OpenAI 兼容 HTTP 协议**解耦，Java 调用 Agent 不感知内部 LangGraph 细节
- Agent 不直接操作数据库，所有数据读写通过 **6 个 MCP 服务**（封装 Java Core 接口）完成，AI 层与业务层可独立扩缩容

## 核心工作内容

**1. 设计三层分级记忆架构，解决跨会话事实连贯问题**

- 短期（最近 40 轮原文，PostgreSQL）+ 长期（PolarDB 向量引擎 TopK 语义检索）+ 用户画像，三层分级注入上下文
- 以 `userId`、`characterId`、`roleId` 等结构化字符串作为 PolarDB `runId` 命名空间，将短对话 / 群聊 / 星梦等 **7 种场景**彻底隔离，消除跨场景记忆污染

**2. 实现 LLM 驱动的记忆事实提取与两级去重合并**

- 利用 PolarDB Memory API 驱动 LLM 对每条新事实做 **ADD / UPDATE / NONE** 三路决策，实现实时去重
- 引入 **miniMerge 滚动收敛**机制，以 PostgreSQL 行数触发合并，`mergeMemories` 语义融合
- LLM 失败自动降级存原文并打标，XXL-Job 定时补跑 retry 接口，保障记忆写入 SLA

**3. 基于 LangGraph 构建多 Agent 编排框架，深度集成 MCP 工具集**

- 5 类业务场景各设独立 Agent（陌生角色 Feed / 熟人 Feed / 星梦批量生产 / 开场白改写 / 意图识别）
- `StateGraph + add_conditional_edges` 实现节点级错误路由与短路退出
- 统一封装 MCPClient，6 个 MCP 服务通过工具名路由表自动调度
- `asyncio.Semaphore` 统一限制 LLM 与 MCP 并发，防止 Serverless 实例内存溢出

**4. 设计五阶段星梦日内容批处理流水线，实现每日千级内容自动生产**

- 5 阶段流水线：扫描升级角色 → 批量改写旧内容 → 时间窗缺口分析 → 全覆盖补漏 → 星事钩子补充，保障全天 **12 个时间窗**内容全覆盖
- 采用「**库存优先、LLM 兜底**」策略，存量复用率 **60%+**，单用户每日 Token 成本降低约 **40%**
- XXL-Job 调度多线程并行处理，支持失败自动重试

**5. 建立 XML 结构化 Prompt 体系与六层 JSON 解析容错机制**

- 统一 12 节语义标签 XML 提示词，多版本并存管理，支持灰度切换
- 针对 DeepSeek 思维链模型输出 `<think>` 块、LLM 偶发尾逗号 / 截断等问题（初始解析失败率约 **8%**），设计六层递进式容错：
  > 剥离 think 块 → Markdown 围栏 → 边界定位 → 标准解析 → 尾逗号修复 → json-repair
- 将解析失败率降至 **< 0.5%**

**6. 构建高可靠 Agent 调度体系，覆盖幂等、限流与异步解耦**

- RocketMQ At-Least-Once 在 Rebalance 窗口期、ACK 丢失、消费超时三类场景必然触发重投，以 **Redis SET NX**（TTL 7 天）保障消费幂等
- **Redisson 分布式锁**（Watch Dog 自动续期）防止星梦会话并发创建
- 星梦入口三级限流（10 分钟冷却 / 每日 8 次 / 拒绝后 5 分钟冻结），分别覆盖误操作、日累计滥用、脚本重试三类场景
- 多类后处理任务经 RocketMQ 异步并行剥离主链路，响应控制在 **30ms** 内
