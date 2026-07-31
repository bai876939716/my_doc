# AI 多智能体开发框架对比文档：CrewAI / AutoGen / OpenAI Swarm / MetaGPT / PydanticAI
> 文档版本：V2.0
> 适用场景：框架学习、技术选型、项目落地参考
> 内容包含：基础介绍、核心架构、代码示例、特性对比、选型指南（MetaGPT / PydanticAI 详细内容见独立文档）

---

## 目录
1. [整体概述](#1-整体概述)
2. [CrewAI 详细说明](#2-crewai-详细说明)
3. [AutoGen 详细说明](#3-autogen-详细说明)
   - [3.3 Team 团队类型（AutoGen 0.4+）](#33-team-团队类型autogen-04)
4. [OpenAI Swarm 详细说明](#4-openai-swarm-详细说明)
5. [MetaGPT 详细说明](#5-metagpt-详细说明) · 详见 [metagpt-角色体系.md](metagpt-角色体系.md) / [metagpt-代码示例.md](metagpt-代码示例.md)
6. [PydanticAI 详细说明](#6-pydanticai-详细说明) · 详见 [pydanticai-依赖注入.md](pydanticai-依赖注入.md) / [pydanticai-代码示例.md](pydanticai-代码示例.md)
7. [五大框架综合对比表](#7-五大框架综合对比表)
8. [场景化选型指南](#8-场景化选型指南)
9. [总结](#9-总结)

---

## 1. 整体概述
当前主流五大 Python 多智能体（Multi-Agent）开发框架定位区分：
- **CrewAI**：角色化团队协作框架，主打**结构化流程、生产级落地**，适合固定流水线任务。
- **AutoGen（微软）**：对话式群聊框架，主打**自由协商、人机交互、代码执行**，适合复杂开放式任务、研发与研究场景。
- **OpenAI Swarm**：轻量接力式框架，主打**极简设计、智能体交接**，适合短流程、轻量应用、快速原型。
- **MetaGPT（DeepWisdom）**：SOP 驱动的软件开发框架，主打**全流程代码与文档自动化**，专为软件研发场景设计。
- **PydanticAI（Pydantic 官方）**：类型安全的 Agent 框架，主打**强类型约束、结构化输出、Pythonic 体验**，适合生产级应用开发。

五大框架均以 Python 为主，生态各有侧重，无绝对优劣，以业务场景作为核心选型依据。

---

## 2. CrewAI 详细说明
### 2.1 基础信息
- 开发团队：João Moura 社区团队，2023 年开源
- 底层依赖：基于 LangChain 生态，**模型无关**，兼容 OpenAI、Anthropic、开源本地大模型
- 核心定位：面向企业生产的**角色驱动型多智能体框架**
- 设计理念：模拟现实团队分工，Agent 拥有固定角色、目标与职责，任务按预设流程有序执行。

### 2.2 核心组件
1. **Agent**：角色化智能体，包含 `role` 角色、`goal` 目标、`backstory` 背景、`tools` 工具集
2. **Task**：最小执行单元，绑定 Agent，支持上下文依赖、输出格式约束
3. **Crew**：智能体+任务的集合，统一调度入口
4. **Process**：执行模式，支持串行、并行、层级执行
5. **Flow**：高阶能力，用于构建带状态、事件驱动的复杂嵌套流程

### 2.3 核心特性
- 分工明确，输出结果稳定、可复现
- 声明式编排，只需定义角色与任务，无需手动管理对话流转
- 内置短期/长期记忆、任务重试、日志监控
- 海量第三方工具集成，支持数据库、搜索、办公软件、API 对接
- 学习门槛低，业务人员也可快速理解角色分工逻辑

### 2.4 完整可运行代码示例
```python
# 安装依赖：pip install crewai crewai-tools
from crewai import Agent, Task, Crew, Process
from crewai_tools import SerperDevTool
import os

# 配置密钥（替换为自己的 Key）
os.environ["OPENAI_API_KEY"] = "你的OpenAI Key"
os.environ["SERPER_API_KEY"] = "你的Serper搜索Key"

# 1. 初始化工具
search_tool = SerperDevTool()

# 2. 定义智能体
research_agent = Agent(
    role="行业研究员",
    goal="搜集2026年AI多智能体框架行业信息",
    backstory="资深互联网行业研究员，擅长信息检索与整理",
    tools=[search_tool],
    verbose=True
)

write_agent = Agent(
    role="文档撰写师",
    goal="根据调研内容输出精简行业总结",
    backstory="专业文案，擅长结构化内容输出",
    verbose=True
)

# 3. 定义任务
task_research = Task(
    description="搜索并整理 2026 年主流 AI 智能体框架市场现状",
    expected_output="要点式信息清单",
    agent=research_agent
)

task_write = Task(
    description="基于调研结果，撰写300字以内行业总结",
    expected_output="标准文本总结",
    agent=write_agent,
    context=[task_research]
)

# 4. 组建团队并执行
crew = Crew(
    agents=[research_agent, write_agent],
    tasks=[task_research, task_write],
    process=Process.sequential  # 串行执行
)

# 启动任务
if __name__ == "__main__":
    result = crew.kickoff()
    print("最终输出：\n", result)
```

### 2.5 适用场景
- 企业固定流程自动化：周报、报表、工单处理
- 内容批量生产：文章、文案、行业研报
- 标准化调研、竞品分析、数据整理
- 要求高稳定性、可监控、长期运维的生产环境

### 2.6 优缺点
- 优点：结构清晰、稳定可靠、生产落地友好、Token 消耗适中
- 缺点：流程固化，不擅长开放式自由讨论，动态分支能力较弱

---

## 3. AutoGen 详细说明
### 3.1 基础信息
- 开发团队：微软亚洲研究院，前身为 FLAML，2023 年正式开源
- 底层依赖：模型无关，原生支持代码沙箱、群聊管理
- 核心定位：**对话驱动型多智能体框架**，主打自由交互与人机协同
- 设计理念：`Conversation Programming`（对话即编程），多个 Agent 像团队开会一样自由交流、协商、迭代解决问题。

### 3.2 核心组件
1. **AssistantAgent**：纯 AI 智能体，负责思考、推理、调用工具
2. **UserProxyAgent**：用户/系统代理，核心能力：执行代码、触发工具、人工介入审批
3. **GroupChat**：多智能体群聊容器，管理会话消息
4. **GroupChatManager**：群聊管理员，负责分配发言权限、控制会话轮次

### 3.3 Team 团队类型（AutoGen 0.4+）
AutoGen 0.4 重构引入了 **Team（团队）** 概念，将多智能体协作方式显式建模为不同类型的团队。Team 统一管理消息流转、终止条件与运行生命周期，通过 `run()` / `run_stream()` 启动。

#### 终止条件（Termination Condition）
Team 运行时需配合终止条件判断何时停止：

| 终止条件 | 说明 |
| ---- | ---- |
| `MaxMessageTermination(n)` | 消息轮次达到 n 次时停止 |
| `TextMentionTermination("关键词")` | 消息中出现指定关键词时停止 |
| `StopMessageTermination()` | Agent 主动发出 StopMessage 时停止 |

终止条件支持逻辑组合：`cond1 | cond2`（任一满足）、`cond1 & cond2`（同时满足）。

#### 四种核心 Team 类型

**1. RoundRobinGroupChat（轮转群聊）**
- 智能体按注册顺序依次循环发言，流程简单可控
- 适合有明确顺序依赖的多步骤任务（数据采集 → 分析 → 报告）

```python
# pip install autogen-agentchat
from autogen_agentchat.teams import RoundRobinGroupChat
from autogen_agentchat.conditions import MaxMessageTermination

team = RoundRobinGroupChat(
    participants=[researcher, analyst, writer],
    termination_condition=MaxMessageTermination(9)  # 每人3轮后结束
)
result = await team.run(task="分析2026年AI多智能体框架趋势")
```

**2. SelectorGroupChat（选择器群聊）**
- 由 LLM 或自定义选择函数动态决定每轮发言的智能体
- 可根据上下文自动路由到最合适的 Agent，灵活性高
- 适合复杂多角色协作、需要智能调度的研究/决策任务

```python
from autogen_agentchat.teams import SelectorGroupChat
from autogen_agentchat.conditions import TextMentionTermination

team = SelectorGroupChat(
    participants=[researcher, analyst, critic],
    model_client=model_client,       # 用于动态选择下一个发言者的模型
    termination_condition=TextMentionTermination("TERMINATE")
)
result = await team.run(task="对这份商业方案进行多角色评审")
```

**3. Swarm（群体接力）**
- Agent 通过主动发出 `HandoffMessage` 将控制权转移给指定智能体
- 接力链路清晰，类似 OpenAI Swarm 的 Handoff 机制，但支持更复杂的路由逻辑
- 适合多阶段流水线，每个 Agent 处理完本职工作后主动交棒

```python
from autogen_agentchat.teams import Swarm
from autogen_agentchat.conditions import StopMessageTermination

# Agent 在 system_message 中描述何时 handoff 给谁
# 触发 HandoffMessage(target="agent_name") 即完成控制权转移
team = Swarm(
    participants=[intake_agent, processing_agent, output_agent],
    termination_condition=StopMessageTermination()
)
result = await team.run(task="处理一个客户投诉工单")
```

**4. MagenticOneGroupChat（MagenticOne 协调群聊）**
- 内置 **Orchestrator（协调者）** 负责任务规划与进度追踪
- 协调者将复杂任务分解后分配给专业 Agent，并汇总最终结果
- 自主性最强，适合多步骤、高复杂度的端到端任务
- 典型场景：自主软件开发、多工具链协作、复杂报告生成

```python
# pip install autogen-ext
from autogen_ext.teams.magentic_one import MagenticOneGroupChat

team = MagenticOneGroupChat(
    participants=[web_surfer, file_surfer, coder, terminal],
    model_client=model_client
)
result = await team.run(task="帮我调研并生成一份竞品分析报告")
```

#### 四种 Team 类型横向对比
| 维度 | RoundRobin | Selector | Swarm | MagenticOne |
| ---- | ---- | ---- | ---- | ---- |
| 发言顺序 | 固定轮转 | LLM 动态选择 | Agent 主动交接 | 协调者统一调度 |
| 灵活程度 | 低 | 高 | 中 | 极高（自主规划）|
| 适用任务 | 有序多步骤 | 复杂多角色协作 | 多阶段流水线 | 端到端复杂任务 |
| 上手难度 | 低 | 中 | 中 | 高 |
| Token 消耗 | 低 | 中 | 中 | 高 |

---

### 3.4 核心特性
- 无固定执行流程，Agent 自主协商流转，灵活性拉满
- **原生强支持人机交互（Human-in-the-loop）**，可随时人工干预、审批、补充信息
- 内置安全代码沙箱，擅长代码编写、数据分析、脚本运行
- 支持可视化工作台 AutoGen Studio，拖拽编排、调试便捷
- 适配全品类大模型，本地开源模型可无缝接入

### 3.5 完整可运行代码示例
```python
# 安装依赖：pip install pyautogen
from autogen import AssistantAgent, UserProxyAgent, GroupChat, GroupChatManager

# 1. 模型配置（替换为自己的 Key）
llm_config = {
    "config_list": [
        {
            "model": "gpt-4o",
            "api_key": "你的OpenAI Key"
        }
    ],
    "temperature": 0.7
}

# 2. 创建智能体
researcher = AssistantAgent(
    name="行业研究员",
    system_message="你负责搜集、梳理AI多智能体框架相关信息，只输出客观内容。",
    llm_config=llm_config
)

analyst = AssistantAgent(
    name="行业分析师",
    system_message="你负责对搜集的信息进行解读、分析，输出观点与总结。",
    llm_config=llm_config
)

# 用户代理：开启代码执行 + 人工介入
user_proxy = UserProxyAgent(
    name="用户代理",
    human_input_mode="TERMINATE",  # 仅会话结束时询问人工
    code_execution_config={
        "work_dir": "autogen_workspace",
        "use_docker": False
    }
)

# 3. 配置群聊
group_chat = GroupChat(
    agents=[user_proxy, researcher, analyst],
    messages=[],
    max_round=10
)

chat_manager = GroupChatManager(
    groupchat=group_chat,
    llm_config=llm_config
)

# 4. 启动会话
if __name__ == "__main__":
    user_proxy.initiate_chat(
        manager=chat_manager,
        message="分析2026年主流AI多智能体框架的特点与适用场景"
    )
```

### 3.6 适用场景
- 复杂开放式任务：论文撰写、商业方案、创意策划、需求研讨
- 全流程软件开发：产品、编码、测试、文档协作
- 必须人工审批、干预、决策的业务场景
- AI 智能体方向技术研究、原型探索、能力验证

### 3.7 优缺点
- 优点：灵活性极强、人机交互完善、代码执行能力顶尖、创意类任务表现好
- 缺点：多轮对话导致 Token 消耗高、输出结果存在波动、生产级运维能力需要自研

---

## 4. OpenAI Swarm 详细说明
### 4.1 基础信息
- 开发团队：OpenAI 官方，2024 年推出的轻量框架
- 底层依赖：深度绑定 OpenAI 模型生态，无额外重型依赖
- 核心定位：**极简接力式多智能体框架**
- 设计理念：以 `Handoff（任务接力）` 为核心，智能体完成子任务后，自动将上下文与对话权交接给下一个智能体。

### 4.2 核心组件
1. **Agent**：轻量化智能体，仅包含名称、指令、工具，无复杂角色配置
2. **Swarm**：框架核心客户端，管理智能体注册、消息流转、交接逻辑
3. **Handoff**：智能体之间的控制权交接机制
4. **Context Variables**：全局上下文变量，用于跨智能体数据共享

### 4.3 核心特性
- 架构极简，仅两个核心类，代码量极少，上手零门槛
- 原生对接 OpenAI Function Call，工具调用流畅
- 无状态设计，每次运行相互独立，测试、部署简单
- 执行链路清晰，日志直观，问题排查效率高
- 专注线性/简单分支流程，轻量化任务成本极低

### 4.4 完整可运行代码示例
```python
# 安装依赖：pip install openai-swarm
from swarm import Swarm, Agent

# 1. 自定义工具
def search_info(query: str) -> str:
    """模拟信息检索工具"""
    return f"【检索结果】针对「{query}」整理出基础行业信息。"

# 2. 定义智能体
agent_research = Agent(
    name="信息检索员",
    instructions="调用工具获取行业基础信息，完成后交接给文案人员。",
    tools=[search_info]
)

agent_writer = Agent(
    name="短文撰写员",
    instructions="根据已有信息，撰写一段简短介绍文案。"
)

# 3. 初始化客户端
client = Swarm()

# 4. 执行任务
if __name__ == "__main__":
    messages = [{"role": "user", "content": "简单介绍AI多智能体框架"}]
    # 启动流程
    response = client.run(
        agent=agent_research,
        messages=messages
    )
    # 打印最终结果
    print("输出结果：\n", response.messages[-1]["content"])
```

### 4.5 适用场景
- 线性短流程业务：在线客服、咨询工单、售后流转
- 简单内容流水线：选题→搜集→写作→润色
- 快速搭建原型、演示 Demo、轻量小型应用
- 纯 OpenAI 生态项目，追求低依赖、快速上线

### 4.6 优缺点
- 优点：极简轻量、学习成本最低、调试简单、运行开销小
- 缺点：功能单一，不支持复杂流程与大规模协作，跨模型兼容性差

---

## 5. MetaGPT 详细说明
### 5.1 基础信息
- 开发团队：DeepWisdom（中国），2023 年开源，GitHub Star 超 45k
- 安装：`pip install metagpt`
- 底层依赖：支持 OpenAI、Anthropic、本地开源模型，内置文件系统与代码执行环境
- 核心定位：**SOP 驱动的软件开发自动化多智能体框架**
- 设计理念：将现实软件公司的分工流程编码为 AI 角色协作，输入一句话需求，自动输出完整代码库与工程文档。

### 5.2 核心角色体系
MetaGPT 将软件团队映射为五类标准角色，角色间通过**共享消息池（Shared Message Pool）**传递结构化产物：

| 角色 | 职责 | 输出产物 |
| ---- | ---- | ---- |
| ProductManager | 需求分析，撰写 PRD | PRD 文档 |
| Architect | 系统设计，接口定义 | 系统设计文档 |
| ProjectManager | 任务拆解，工程规划 | 任务清单 |
| Engineer | 按设计实现代码 | 源代码文件 |
| QaEngineer | 编写并执行测试 | 测试代码与报告 |

> 角色机制详解、消息订阅模型、自定义角色 → [metagpt-角色体系.md](metagpt-角色体系.md)

### 5.3 核心特性
- **SOP 全链路自动化**：需求 → PRD → 系统设计 → 代码 → 测试，每步产物结构化可追溯
- **共享消息池架构**：角色订阅感兴趣的消息类型，发布/订阅解耦，天然支持并行执行
- **结构化产物输出**：角色输出有模式约束（非自由文本），下游角色可直接消费
- **内置代码执行**：Engineer 生成代码后可自动运行、校验、修复
- **可扩展角色与 Action**：自定义角色继承 `Role`，自定义动作继承 `Action`，扩展门槛低

### 5.4 快速示例
```python
# 安装依赖：pip install metagpt
import asyncio
from metagpt.roles import ProductManager, Architect, ProjectManager, Engineer, QaEngineer
from metagpt.team import Team

async def main(idea: str):
    team = Team()
    team.hire([
        ProductManager(),
        Architect(),
        ProjectManager(),
        Engineer(n_borg=3),   # 3 个并行 Engineer
        QaEngineer(),
    ])
    team.invest(investment=3.0)   # 预算上限（美元）
    team.run_project(idea)
    await team.run(n_round=5)

asyncio.run(main("开发一个支持增删改查的命令行 Todo 管理工具"))
```

> 完整示例（自定义 Role、Action、消息路由配置）→ [metagpt-代码示例.md](metagpt-代码示例.md)

### 5.5 适用场景
- 软件原型自动生成：一句话需求快速产出完整可运行项目
- 技术文档自动化：PRD、架构设计、API 文档全自动生成
- 测试用例生成：QaEngineer 自动分析代码并生成测试
- LLM 能力研究：探索大模型在复杂工程任务上的边界

### 5.6 优缺点
- **优点**：软件开发场景能力顶尖、结构化产物质量高、开箱即用
- **缺点**：强绑定软件开发范式、非开发类任务适配成本高、Token 消耗极大

---

## 6. PydanticAI 详细说明
### 6.1 基础信息
- 开发团队：Pydantic 官方团队（Samuel Colvin），2024 年 11 月正式开源
- 安装：`pip install pydantic-ai`
- 底层依赖：原生支持 OpenAI、Anthropic、Gemini、Groq、Mistral、Ollama，无重型依赖
- 核心定位：**类型安全的 Python-first AI Agent 开发框架**
- 设计理念：将 Pydantic 数据验证哲学引入 AI 开发，Agent 的依赖注入、输入输出均有强类型约束，构建体验与普通 Python 函数完全一致。

### 6.2 核心组件
1. **Agent**：泛型核心类 `Agent[DepsType, OutputType]`，定义阶段即绑定依赖与输出类型
2. **RunContext**：依赖注入容器 `RunContext[DepsType]`，将数据库、API 客户端等服务传递给工具函数
3. **Tool**：通过 `@agent.tool` / `@agent.tool_plain` 装饰器注册，函数签名自动转为 JSON Schema
4. **OutputType**：Pydantic BaseModel 子类，强制约束 LLM 最终输出结构，框架自动解析验证
5. **ModelSettings**：模型参数配置（temperature、max_tokens 等），与业务逻辑完全解耦

### 6.3 核心特性
- **端到端类型安全**：泛型 Agent 在 IDE 中即有完整类型提示，运行时 Pydantic 自动验证输出
- **依赖注入系统**：通过 `RunContext[Deps]` 将数据库、缓存、配置等注入工具，便于解耦与测试
- **结构化输出**：`output_type=YourModel`，LLM 被约束输出合法 JSON，框架自动解析
- **测试友好**：内置 `TestModel`（不发 API 请求）与 `FunctionModel`，CI 中无 API 成本测试全部逻辑
- **模型无关**：`"openai:gpt-4o"` / `"anthropic:claude-opus-4-7"` 一行切换，接口完全统一
- **同步/异步统一**：`run_sync()` / `run()` 接口一致，灵活适配任意运行环境

### 6.4 快速示例
```python
# 安装依赖：pip install pydantic-ai
from pydantic_ai import Agent
from pydantic import BaseModel

class ResearchResult(BaseModel):
    summary: str
    key_points: list[str]
    confidence: float   # 0.0 - 1.0

agent: Agent[None, ResearchResult] = Agent(
    "openai:gpt-4o",
    output_type=ResearchResult,
    system_prompt="你是专业研究员，以结构化 JSON 格式输出调研结论。"
)

result = agent.run_sync("分析 2026 年 AI 多智能体框架发展趋势")
print(result.output.summary)       # str，IDE 有类型提示
print(result.output.key_points)    # list[str]
```

> 完整示例（依赖注入、多 Agent 编排、流式输出、TestModel 测试）→ [pydanticai-代码示例.md](pydanticai-代码示例.md)

### 6.5 适用场景
- 结构化数据提取：合同解析、表单填充、报告结构化输出
- 生产级业务系统：金融、医疗、合规等对类型正确性有强要求的场景
- 已有 Pydantic 技术栈的项目：零迁移成本接入 AI 能力
- 高测试覆盖率项目：TestModel 让 Agent 逻辑可在 CI 中无 API 成本地完整测试

### 6.6 优缺点
- **优点**：类型安全极强、Pythonic 体验最佳、测试支持完善、模型切换零成本
- **缺点**：原生多 Agent 编排能力弱（需手动 Python 调用编排）、社区尚在成长

---

## 7. 五大框架综合对比表
| 对比维度 | CrewAI | AutoGen | OpenAI Swarm | MetaGPT | PydanticAI |
| ---- | ---- | ---- | ---- | ---- | ---- |
| 设计范式 | 角色+任务 团队流水线 | 多智能体自由群聊 | 智能体任务接力(Handoff) | SOP 驱动软件开发团队 | 类型安全单/多 Agent |
| 流程控制 | 强结构化，串行/并行/层级 | 无固定流程，动态协商 | 线性流程、简单分支 | 固定软件研发 SOP | 手动 Python 函数编排 |
| 灵活程度 | 低 | 极高 | 中 | 极低（专注软件开发）| 高（纯 Python） |
| 人机交互(Human-in-loop) | 支持，非核心能力 | **原生核心能力** | 弱支持 | 弱 | 中等 |
| 代码执行能力 | 中等（依赖第三方工具） | **极强（内置沙箱）** | 弱（仅基础函数调用） | 强（Engineer 角色） | 需自实现 |
| Token 消耗 | 中等 | 偏高（多轮对话） | 偏低 | **极高**（全链路文档） | 低（精准调用） |
| 学习曲线 | 低 | 中高 | 极低 | 中（需理解 SOP 体系） | 低（Pythonic） |
| 生产部署能力 | 企业级完善，自带监控/重试 | 研究向，企业能力需自研 | 轻量可用，适合小型应用 | 特定场景（软件开发） | 生产友好，类型安全 |
| 模型兼容性 | 全模型兼容 | 全模型兼容 | 优先适配 OpenAI 系列 | 主要 OpenAI，兼容其他 | 全模型兼容 |

---

## 8. 场景化选型指南
### 8.1 优先选择 CrewAI
1. 业务为**固定、重复、标准化**流程
2. 项目需要长期生产部署、运维、监控
3. 团队希望逻辑直观，降低后续维护成本
4. 场景：报表、研报、批量内容、企业自动化流程

### 8.2 优先选择 AutoGen
1. 任务**开放、复杂、需要多轮讨论与创意**
2. 流程中存在大量**人工审批、干预、决策**环节
3. 需要 AI 完成代码编写、数据分析、脚本运行
4. 场景：科研、方案策划、软件开发、技术原型验证

### 8.3 优先选择 OpenAI Swarm
1. 仅需**简单线性流转**，步骤少、链路短
2. 基于 OpenAI 生态，追求极简代码、快速上线
3. 轻量化 Demo、内部小工具、客服咨询链路
4. 预算/资源有限，希望降低框架复杂度

### 8.4 优先选择 MetaGPT
1. 核心需求是**自动化软件开发、代码生成、技术文档输出**
2. 有充足 API 预算，接受较高 Token 消耗
3. 需要从需求到代码的端到端自动化验证
4. 场景：软件原型快速生成、内部工具自动构建、技术文档自动化

### 8.5 优先选择 PydanticAI
1. 业务对输出格式有**强类型约束**，不能接受格式错误
2. 项目已使用 Pydantic，希望零迁移成本接入 AI
3. 需要高覆盖率 CI 测试，不希望每次都调用 LLM API
4. 场景：结构化数据提取、生产级 API 服务、合规场景

### 8.6 混合架构方案（高阶落地）
- 主业务流水线：使用 CrewAI 保证稳定高效
- 复杂决策/创意节点：嵌入 AutoGen 群聊能力
- 外围简单分支流程：使用 Swarm 轻量化接力
- 自动化软件开发节点：接入 MetaGPT 批量生成代码
- 对输出结构有强约束的节点：使用 PydanticAI 保障类型安全

---

## 9. 总结
1. **CrewAI = 工业流水线**：稳定、规范、面向规模化生产，是企业落地首选。
2. **AutoGen = 专家会议室**：灵活、自由、擅长复杂问题与人机协同，偏向研究与创意场景。
3. **OpenAI Swarm = 简易传送带**：轻量、快速、上手无门槛，适合小型应用与快速原型。
4. **MetaGPT = 软件工程团队**：SOP 驱动、全链路文档化，专为软件开发自动化打造，能力聚焦但场景内天花板极高。
5. **PydanticAI = 严谨的类型工程师**：类型安全第一、Pythonic 体验最佳，生产级 Agent 系统的可靠之选。

五大框架均以 Python 为主，无绝对优劣，**匹配业务场景**是选型第一原则。可根据项目阶段灵活组合：结构化流程用 CrewAI、创意复杂任务用 AutoGen、快速原型用 Swarm、软件生成用 MetaGPT、类型严格的生产系统用 PydanticAI。