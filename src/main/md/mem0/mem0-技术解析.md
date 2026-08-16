# Mem0 技术全解

> 面向程序员视角：架构原理 · 核心类设计 · 执行流程 · 工程实践

---

## UML 图索引

所有图表位于 `diagrams/` 文件夹。

| 编号 | 文件 | 类型 | 内容 |
|------|------|------|------|
| 01 | `diagrams/01-系统架构图.puml` | Component | 整体组件架构：接入层 → 核心调度层 → 能力层 → 存储层 |
| 02 | `diagrams/02-核心类图.puml` | Class | Memory 主类 + 抽象基类 + 可插拔实现类 |
| 03 | `diagrams/03-记忆写入时序图.puml` | Sequence | add 接口：事实提取 → 冲突决策 → 多库持久化 |
| 04 | `diagrams/04-记忆检索时序图.puml` | Sequence | search 接口：向量召回 → 图谱增强 → 多维重排序 |

---

## 一、产品定位与核心价值

### 1.1 定义

Mem0 是面向 **AI Agent / LLM 应用** 的**开源生产级长期记忆中间件**，专门解决大模型上下文窗口有限、会话隔离、无法长期记忆用户信息的问题。

- **定位**：独立可插拔记忆层，不侵入原有 LLM/Agent 业务逻辑
- **部署形态**：云端托管服务 + 本地私有化部署双模式
- **技术底座**：大模型 + 向量检索 + 元数据数据库 + 实体图谱混合架构

### 1.2 行业痛点

| 问题 | 说明 |
|------|------|
| 跨会话失忆 | 原生 LLM 仅依赖会话上下文，跨会话、跨设备完全失忆 |
| 传统 RAG 局限 | 偏向静态文档检索，不支持记忆更新、冲突消解、偏好迭代 |
| 自研成本高 | 需重复实现提取、去重、更新、检索、权限、TTL 等逻辑 |
| 多租户缺失 | 无统一多维度记忆隔离能力，不适合企业级多 Agent 场景 |

### 1.3 核心能力

- 对话自动事实提取、结构化记忆存储
- 记忆智能更新、去重、冲突消解、过期淘汰
- 多维度权限隔离：用户 / 会话 / Agent / 组织四级隔离
- 语义检索 + 多权重排序（相似度 / 时间 / 重要性 / 实体关联）
- 全组件可插拔：LLM / Embedding / 向量库支持主流生态

---

## 二、整体系统架构

> 图示见 `diagrams/01-系统架构图.puml`

架构分四层，自上而下：

| 层级 | 组件 | 职责 |
|------|------|------|
| **接入层** | SDK / HTTP API | Python/TS SDK、标准 HTTP API，对外统一入口 |
| **核心调度层** | Memory 主类 | 串联所有模块，控制全流程流转 |
| **能力层** | LLM / Embedding / Reranker | LLM 语义理解与决策、Embedding 向量转换、Reranker 优化检索精度 |
| **存储层** | VectorStore / GraphStore / SQLite | 分别承载语义检索、实体关系推理、元数据与生命周期管理 |

---

## 三、核心模块与类设计

> 图示见 `diagrams/02-核心类图.puml`

### 3.1 设计原则

- **抽象基类 + 工厂模式**：`BaseLLM`、`BaseEmbedding`、`BaseVectorStore`、`BaseGraphStore` 四个抽象接口，所有底层组件可无缝替换（开闭原则）
- **MemoryConfig**：全局配置中心，统一管理组件类型、存储路径、功能开关
- **Memory 主类**：对外唯一入口，封装所有记忆增删改查逻辑，内部协调各组件

### 3.2 可插拔实现

| 抽象基类 | 可替换实现 |
|----------|-----------|
| `BaseLLM` | OpenAI、Anthropic、Azure、开源本地大模型 |
| `BaseEmbedding` | OpenAI、Cohere、BGE、本地嵌入模型 |
| `BaseVectorStore` | Qdrant、Faiss、pgvector、LanceDB、Chroma |
| `BaseGraphStore` | Neo4j 等图数据库，可按需关闭图谱能力 |

---

## 四、核心执行流程

### 4.1 记忆写入（add 接口）

> 图示见 `diagrams/03-记忆写入时序图.puml`

完整链路：`对话输入 → 事实提取（LLM #1）→ 向量化 → 相似匹配 → 冲突决策（LLM #2）→ 多库持久化`

**四元决策规则（LLM 驱动）：**

| 决策 | 触发条件 | 执行动作 |
|------|---------|---------|
| `ADD` | 全新事实，向量库中无相似内容 | 直接新增 |
| `UPDATE` | 旧信息过时或新版本更具体 | 用新内容覆盖旧记忆 |
| `DELETE` | 新旧内容互相矛盾 | 删除旧记忆 |
| `NOOP` | 完全重复，与已有记忆高度相似 | 跳过，不处理 |

**多库存储分工：**

| 存储 | 内容 | 用途 |
|------|------|------|
| VectorStore | 向量 + 检索索引 | 语义相似度检索 |
| GraphStore | 实体节点 + 关系边 | 多跳实体关联推理 |
| SQLite | 元数据、标签、时间戳、TTL | 权限隔离、生命周期管理 |

### 4.2 记忆检索（search 接口）

> 图示见 `diagrams/04-记忆检索时序图.puml`

完整链路：`Query 向量化 → 向量粗召回 → 图谱实体增强 → 多维重排序 → Top-K 结果`

**多维重排序打分维度：**

1. **语义相似度**：向量余弦距离，最直接的相关性信号
2. **时间衰减**：新记忆权重更高，旧记忆自然降权
3. **重要性标记**：人工或模型标注的高价值记忆优先
4. **实体关联度**：图谱中与查询实体的关联强度

---

## 五、核心技术原理

### 5.1 整体流转链路

```
对话输入 → 事实提取 → 冲突决策 → 数据持久化 → 检索召回 → 重排序 → 注入 Prompt
```

### 5.2 多租户隔离设计

四级维度隔离，自由组合过滤：

| 维度 | 标识 | 说明 |
|------|------|------|
| 用户维度 | `user_id` | 跨会话永久记忆，最常用隔离键 |
| 智能体维度 | `agent_id` | 不同 Agent 实例的记忆相互隔离 |
| 会话维度 | `session_id / run_id` | 单次会话内的记忆，生命周期短 |
| 组织维度 | `app_id / org_id` | 企业级全局隔离 |

### 5.3 冲突消解与数据治理

- **时序优先**：新产生的事实默认覆盖旧事实
- **语义冲突**：LLM 判定内容真伪，自动清理矛盾记忆
- **冗余合并**：相似语义记忆自动合并，减少存储体积与检索冗余
- **TTL 过期淘汰**：支持配置记忆生命周期，自动清理过期数据

---

## 六、代码示例

### 6.1 安装

```bash
pip install mem0ai
```

### 6.2 基础使用

```python
from mem0 import Memory
from mem0.configs.base import MemoryConfig

config = MemoryConfig(
    llm="openai",
    embedder="openai",
    vector_store="qdrant",
    history_db_path="./mem0_local.db",
    enable_graph=False
)

memory = Memory(config=config)

# 写入记忆
messages = [
    {"role": "user",      "content": "我是素食者，并且对坚果类食物过敏"},
    {"role": "assistant", "content": "了解，我会记住你的饮食禁忌与偏好"}
]
memory.add(messages=messages, user_id="user_001", agent_id="agent_demo_01")

# 检索记忆
results = memory.search(query="帮我推荐合适的餐厅", user_id="user_001", limit=3)
for item in results:
    print(f"内容：{item['content']}  置信分：{item['score']}")
```

### 6.3 与 LangGraph Agent 集成

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Annotated
import operator
from mem0 import Memory

mem = Memory()

class AgentState(TypedDict):
    query: str
    user_id: str
    history: Annotated[list, operator.add]

def query_with_memory(state: AgentState):
    memories = mem.search(query=state["query"], user_id=state["user_id"])
    memory_text = "\n".join([m["content"] for m in memories])
    full_prompt = f"历史记忆：{memory_text}\n用户当前问题：{state['query']}"
    return {"history": [full_prompt]}

builder = StateGraph(AgentState)
builder.add_node("memory_node", query_with_memory)
builder.add_edge(START, "memory_node")
builder.add_edge("memory_node", END)
graph = builder.compile()
```

---

## 七、部署与工程实践

### 7.1 部署模式

| 模式 | 适用场景 | 特点 |
|------|---------|------|
| 云端托管 | 快速上线、原型验证 | 仅需配置 API Key，零运维 |
| 私有化部署 | 企业私密数据、合规场景 | 对接自研 LLM + 本地向量库，数据完全可控 |

### 7.2 性能优化建议

- **高频检索**：开启内存缓存热点记忆，减少向量库查询压力
- **海量用户**：对 `user_id` 做分片，拆分向量库与元数据库
- **长对话**：配置窗口截断，仅截取近期对话用于事实提取，控制 Token 消耗
- **批量写入**：多条对话合并写入，减少数据库 IO 次数

### 7.3 运维监控

- **核心指标**：记忆写入耗时、检索耗时、LLM 调用失败率、向量库 QPS
- **数据备份**：定期备份 SQLite 元数据与向量库快照
- **权限管控**：基于 `user_id / app_id` 做数据隔离，禁止越权查询

### 7.4 适用场景

- 个性化 AI 助手、智能客服、陪伴型 Agent
- 教育辅导、医疗健康类 LLM 应用（搭配合规能力）
- 企业内部知识库 + 个人偏好结合的综合智能系统
