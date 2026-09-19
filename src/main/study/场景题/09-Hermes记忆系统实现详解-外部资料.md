# Hermes Agent 记忆系统实现详解（纯外部资料整理）

> **说明**：本文内容全部来自互联网公开资料（Nous Research 官方文档、GitHub 源码仓库、第三方技术博客的架构分析），**不参考本地项目任何文件**，作为一份独立的技术研究笔记。所有信息点都标注了来源，方便自行核实。如果要看"怎么把这套机制嫁接到 xinyu 项目"的设计提案，见同目录下 `07-Hermes记忆机制嫁接xinyu设计提案.md`（那篇是设计提案，这篇是纯事实整理）。

---

## 一、整体架构：三层记忆分层，不是单一存储

Hermes Agent（Nous Research 开源的自进化 AI Agent 框架）的记忆能力由三层组成，按"访问频率与容量"分层，而不是把所有历史都塞进同一个存储里：

| 层级 | 组件 | 特点 |
|------|------|------|
| 第一层：内置有界记忆 | `MEMORY.md` + `USER.md` | 容量极小（约1300 tokens），每轮必现，会话开始即注入 |
| 第二层：会话历史检索 | `session_search`（SQLite + FTS5 全文索引） | 容量几乎无限（所有历史会话），按需查询，不做语义摘要 |
| 第三层：外部记忆 Provider（可选插件） | 8 种可插拔后端 | 知识图谱、语义检索、混合检索等更强能力，与内置记忆并存而非替代 |

这种分层设计被外部技术分析称为 **"Memory Stratification Over Monolithic Storage"**（分层记忆优于单一存储）——核心目的是避免"每轮都把所有历史一股脑注入"这种粗暴做法造成的 token 浪费和模型注意力稀释。[来源: luismori.dev 架构分析]

---

## 二、内置记忆的具体实现机制

### 2.1 双文件结构与容量上限

存储在 `~/.hermes/memories/` 目录下：

| 文件 | 内容 | 容量上限 |
|------|------|---------|
| `MEMORY.md` | Agent 自己的环境笔记、项目约定、学到的技巧 | 2,200 字符（约800 tokens） |
| `USER.md` | 用户画像：偏好、沟通风格、期望 | 1,375 字符（约500 tokens） |

[来源: NousResearch/hermes-agent 官方文档 `memory.md`]

### 2.2 冻结快照（Frozen Snapshot）与三层 Prompt 组装

两个文件在会话开始时作为"冻结快照"注入系统提示词——Agent 从第一个 token 开始就拿到这份记忆，但这份快照在**整个会话期间不会更新**，即使会话中用 `memory` 工具修改了记忆、并且已经立即写入了磁盘，也要等**下一个新会话开始**才会生效。

第三方架构分析进一步指出，Hermes 的系统提示词实际按**三层结构**组装（稳定层 / 上下文层 / 易变层），这个设计被称为 **"Prompt Caching as a First-Order Design Constraint"**（Prompt 缓存是第一优先级的设计约束）：

- **稳定层（stable）**：身份设定、技能索引这类几乎不变的内容
- **上下文层（context）**：项目上下文文件等
- **易变层（volatile）**：临时性事实

这样划分是为了保护 LLM 的 **prefix cache** 边界——如果记忆这种易变内容和稳定内容混在一起，哪怕只有一行发生变化，也会让缓存失效，这是"在生产系统里观察到系统提示词每轮都变一行"这种真实教训后得出的设计原则。[来源: luismori.dev 架构分析]

### 2.3 `memory` 工具：add / replace / remove

Agent 通过 `memory` 工具主动管理记忆，三个动作：

| 动作 | 说明 |
|------|------|
| `add` | 在容量范围内新增条目 |
| `replace` | 用 `old_text` 做**子串匹配**定位已有条目并替换，不需要传完整原文，只需要能唯一定位的短语；如果匹配到多条会报错要求更精确的匹配文本 |
| `remove` | 同样用子串匹配定位后删除 |

没有 `read` 动作——因为记忆本身就在系统提示词里，Agent 一直"看得见"，不需要额外查询。[来源: 官方文档 `memory.md`]

### 2.4 容量管理：不自动压缩，靠报错倒逼整理

系统**不会自动做压缩**，当一次写入会导致超过字符上限时，直接返回错误，要求 Agent 在同一轮里先合并/删除旧条目腾出空间再写入——哪怕是"替换"操作，替换后的新内容如果比原来长，也可能因为总量超限而失败，逼着 Agent 主动做取舍。官方最佳实践建议：**记忆占用超过 80% 容量时就应该开始合并相关条目**。[来源: 官方文档]

### 2.5 重复检测

如果新增的内容和已有记忆完全重复，系统会返回"成功"但附带提示"该内容已存在"，不会产生重复条目。[来源: 官方文档]

### 2.6 什么该记、什么不该记

**应该记**：交互中发现的用户偏好、环境事实（操作系统/工具/项目结构）、对早期错误假设的纠正、项目约定、已完成的工作里程碑、用户明确要求记住的信息。

**不该记**：琐碎信息、容易重新查到的通用知识、大段代码原文、会话专属的临时上下文、已经记录在其他系统文件里的内容。[来源: 官方文档]

---

## 三、`session_search`：基于 SQLite FTS5 的全文检索，不做语义压缩

所有 CLI 和消息会话都存储在 SQLite 数据库 `~/.hermes/state.db` 里，用 **FTS5（SQLite 全文搜索扩展）** 建索引。`session_search` 返回的是数据库里的**原始消息**，支持前后翻看上下文，**不经过 LLM 做摘要**——这是一个刻意的设计取舍：

- 好处：没有额外的 embedding 计算成本，没有额外的 LLM 调用开销，查询延迟极低
- 定位：**内置记忆负责"必须一直在上下文里的关键事实"；`session_search` 负责"上次我们聊过什么"这类按需查证的问题**，两者分工明确，不是同一个东西的两种实现

[来源: 官方文档、luismori.dev 架构分析]

---

## 四、后台自我提升学习闭环（Background Self-Improvement Loop）

这是 Hermes"自进化"这个定位的核心机制，也是相对少被提及但技术含量较高的部分：

### 4.1 工作方式

每一轮对话结束后，一个**后台审查任务**会评估这一轮里有没有值得沉淀的信息——可能是一条应该写入 `MEMORY.md` 的事实，也可能是一段值得沉淀为"技能（Skill）"的可复用流程。这个过程默认是**静默**的，不需要用户手动触发。

### 4.2 `write_approval`：写入审批闸门

考虑到"后台自动写入记忆"存在"Agent 保存了错误假设"的风险，系统提供 `write_approval` 开关：开启后，所有的记忆/技能写入操作（无论来自前台对话还是后台自动审查）都不会直接生效，而是进入**待审核队列**，用户可以通过 `/memory pending` 查看并决定是否批准。这被称为 **"Consent-Aware Self-Modification"**（有同意机制的自我修改）——让"学习"变成一个可审计的功能，而不是一个不受控的副作用。[来源: LearnPrompt 技术分析、GitHub Issue 讨论]

### 4.3 成本优化：审查任务可路由到更便宜的模型

后台审查默认用主对话模型执行，但可以通过 `auxiliary.background_review` 配置项路由到一个更便宜的模型，降低这个"隐藏成本"——第三方分析特别提醒，这类后台学习闭环**不是免费的**，团队采用时需要把这部分额外的 LLM 调用计入成本预算，估算下来 Hermes 每轮对话的固定开销（记忆快照+技能索引+上下文文件）大约在 **3,000～5,300 tokens**。[来源: 官方文档、luismori.dev 架构分析]

### 4.4 Memory 和 Skills 的分工

后台学习闭环的产出分两种，职责不同：

- **Memory**：存储"应该一直在上下文里的、小而持久的事实"
- **Skills**：存储"应该按需加载的、更长的过程性知识"（一段可复用的工作流程），系统提示词里只放技能名称和简短描述（一个较大的技能库大概占约3000 tokens），完整的技能内容只有在模型判断需要时才通过 `skill_view()` 按需加载进上下文——这被称为**"渐进式加载（Progressive Disclosure）"**，避免技能库变大后拖慢每一轮的固定开销。[来源: LearnPrompt、luismori.dev]

---

## 五、安全机制

- 记忆条目在**写入前**会做安全扫描，检测提示词注入模式、凭证泄露模式（API Key/密码等）、不可见 Unicode 字符，命中则直接拒绝写入
- 由于记忆会被注入进系统提示词，**加载时也会再扫描一次**，这是防御"记忆投毒"类攻击的双重关卡设计

[来源: 官方文档、WebSearch 综合结果]

---

## 六、外部记忆 Provider 生态（8 种可插拔后端）

内置记忆之外，Hermes 支持 8 个外部记忆插件，**同一时间只能启用一个，且内置记忆始终并行工作、不会被替代**：

| Provider | 存储方式 | 核心特点 |
|----------|---------|---------|
| **Honcho** | 云端/自托管 | 跨会话用户建模，"辩证推理"，冷启动/热启动区分注入策略 |
| **OpenViking** | 自托管（AGPL开源） | 文件系统式知识分层（摘要→概览→全文三级检索），自动抽取6类记忆 |
| **Mem0** | 云端/自托管/进程内 | 服务端LLM自动事实抽取，语义检索与去重，适合"甩手不管"式记忆管理 |
| **Hindsight** | 云端/本地PostgreSQL | 知识图谱+实体解析，独有的`hindsight_reflect`工具做跨记忆综合推理 |
| **Holographic** | 本地SQLite | 完全离线，FTS5全文+HRR（全息归约表示）支持代数式组合查询 |
| **RetainDB** | 云端（$20/月） | Vector+BM25+重排序的混合检索，支持7类记忆和增量压缩 |
| **ByteRover** | 本地/云端 | 面向开发者的可移植分层知识树，上下文压缩前自动提取洞察保留知识 |
| **Supermemory** | 云端/自托管 | 语义长期记忆+会话结束自动构建知识图谱，"上下文围栏"防止已召回记忆被递归再次采集 |

[来源: NousResearch/hermes-agent 官方文档 `memory-providers.md`]

**架构关键点**：外部 Provider 激活后会做五件事——注入上下文、轮次开始前预取相关记忆、轮次结束后同步对话、（部分支持）会话结束时抽取记忆、镜像内置记忆的写入。内置记忆始终不受影响地继续工作。[来源: 官方文档]

---

## 七、值得借鉴的架构原则（第三方深度分析总结）

以下几条来自外部技术博客对 Hermes 架构的评价，是理解这套设计"为什么这么做"的关键：

1. **Memory Stratification Over Monolithic Storage**：按访问频率分层存储，避免"全部塞进一个大记忆"
2. **Prompt Caching as a First-Order Design Constraint**：Prompt 缓存命中率是一等设计目标，不是事后优化
3. **Skills as Procedural Memory, Not Document Retrieval**：技能是"如何做"的流程，不是文档片段检索，渐进式加载
4. **Sessions Own Lineage, Not History Rewrites**：上下文压缩时创建带 `parent_session_id` 的子会话，而不是直接改写原始记录，保留可追溯性（能重建"为什么触发了摘要、哪些消息被裁剪"的完整链条）
5. **Governance Through Artifact Inspection**：记忆、技能、待审批变更都是**可 diff、可 grep、可版本控制的 Markdown/SQLite 文件**，而不是不透明的向量库黑盒——"学习是一个可审计的功能，不是失控的副作用"
6. **Consent-Aware Self-Modification**：自我修改默认可以关审批闸门，防止无监督场景下自主学习变成隐患
7. **Tool Registry ≠ Tool Exposure**：工具在导入时全局注册，但只按平台/Profile 决定是否暴露给模型，"广泛安装、窄口暴露"

[来源: luismori.dev 架构分析]

---

## 八、诚实的局限性（第三方评测指出）

- **不是企业级文档 RAG 的替代品**：提供了钩子（qmd/wiki/记忆Provider插件）但不包含生产级的文档摄取管道、分块策略、引用规范、检索评估体系
- **学习质量仍依赖人工监督**：后台审查可能沉淀低质量记忆或对噪声工作流"过拟合"，审批闸门能兜底，但评估体系需要使用者自己搭建，长期无监督运行质量会退化
- **多子代理编排能力仍在成熟中**：`delegate_task` 已支持任务委派，但独立长时程运行、外部实时干预的复杂多代理编排场景还在完善

[来源: luismori.dev 架构分析]

---

## 九、和其他框架的定位区别

第三方分析把 Hermes 定位为**"个人/团队级 Agent Harness"**，强调"能在自己掌控的基础设施上、跨会话持续积累知识的长期运行助手"，并做了区分：

- 和 **n8n** 不同：n8n 是工作流自动化 + 审批节点，没有持久的 Agent 身份
- 和 **Agno/AgentOS** 不同：那些是带 RBAC 的生产级 Agent API，不是个人化 Harness
- 和 **CrewAI** 不同：CrewAI 面向多 Agent 编排，不是单 Agent 的过程性学习
- 和 **OpenClaw** 不同：以网关为中心的控制面 vs Hermes 以运行时为中心，混合文档检索 vs FTS5

[来源: luismori.dev 架构分析]

---

## 十、小结

Hermes Agent 的记忆系统不是"一个记忆模块"，而是一套分层协作的体系：**极小容量、必现的核心事实（MEMORY.md/USER.md）+ 近乎无限容量、按需检索的原始历史（session_search/FTS5）+ 可插拔的更强记忆后端（8种外部Provider）**，外加一个"后台自动学习但可审批把关"的自我进化闭环。整套设计里最值得学习的两个工程判断是：**把 Prompt Cache 命中率当作记忆系统设计的一等约束**，以及**把"学习"这件事做成可审计（Markdown/SQLite 文件级别的可 diff）而不是不透明的黑盒**。

---

## 参考来源

- [Persistent Memory - Hermes Agent 官方文档](https://hermes-agent.nousresearch.com/docs/user-guide/features/memory)
- [Memory Providers - Hermes Agent 官方文档](https://hermes-agent.nousresearch.com/docs/user-guide/features/memory-providers)
- [NousResearch/hermes-agent GitHub 仓库 - memory.md 源文件](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/memory.md)
- [NousResearch/hermes-agent GitHub 仓库 - memory-providers.md 源文件](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/memory-providers.md)
- [NousResearch/hermes-agent GitHub 主仓库](https://github.com/nousresearch/hermes-agent)
- [Hermes Agent's Learning Loop: Memory, Skills, and Write Approval - LearnPrompt](https://www.learnprompt.pro/en/agent-frameworks/hermes-learning-loop/)
- [Hermes Agent Review: RAG, Architecture, and Lessons for Engineers - luismori.dev](https://luismori.dev/article/hermes-agent-rag-agent-harness-architecture/)
