# AI 多智能体开发框架对比文档：CrewAI / AutoGen / OpenAI Swarm
> 文档版本：V1.0
> 适用场景：框架学习、技术选型、项目落地参考
> 内容包含：基础介绍、核心架构、代码示例、特性对比、选型指南

---

## 目录
1. [整体概述](#1-整体概述)
2. [CrewAI 详细说明](#2-crewai-详细说明)
3. [AutoGen 详细说明](#3-autogen-详细说明)
   - [3.3 Team 团队类型（AutoGen 0.4+）](#33-team-团队类型autogen-04)
4. [OpenAI Swarm 详细说明](#4-openai-swarm-详细说明)
5. [三大框架综合对比表](#5-三大框架综合对比表)
6. [场景化选型指南](#6-场景化选型指南)
7. [总结](#7-总结)

---

## 1. 整体概述
当前主流三大 Python 多智能体（Multi-Agent）开发框架定位区分：
- **CrewAI**：角色化团队协作框架，主打**结构化流程、生产级落地**，适合固定流水线任务。
- **AutoGen（微软）**：对话式群聊框架，主打**自由协商、人机交互、代码执行**，适合复杂开放式任务、研发与研究场景。
- **OpenAI Swarm**：轻量接力式框架，主打**极简设计、智能体交接**，适合短流程、轻量应用、快速原型。

三者均基于大模型构建，语法以 Python 为主，生态各有侧重，无绝对优劣，以业务场景作为核心选型依据。

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

## 5. 三大框架综合对比表
| 对比维度 | CrewAI | AutoGen | OpenAI Swarm |
| ---- | ---- | ---- | ---- |
| 设计范式 | 角色+任务 团队流水线 | 多智能体自由群聊 | 智能体任务接力(Handoff) |
| 流程控制 | 强结构化，串行/并行/层级 | 无固定流程，动态协商 | 线性流程、简单分支 |
| 灵活程度 | 低 | 极高 | 中 |
| 人机交互(Human-in-loop) | 支持，非核心能力 | **原生核心能力** | 弱支持 |
| 代码执行能力 | 中等（依赖第三方工具） | **极强（内置沙箱）** | 弱（仅基础函数调用） |
| Token 消耗 | 中等 | 偏高（多轮对话） | 偏低 |
| 学习曲线 | 低 | 中高 | 极低 |
| 生产部署能力 | 企业级完善，自带监控/重试 | 研究向，企业能力需自研 | 轻量可用，适合小型应用 |
| 模型兼容性 | 全模型兼容 | 全模型兼容 | 优先适配 OpenAI 系列 |

---

## 6. 场景化选型指南
### 6.1 优先选择 CrewAI
1. 业务为**固定、重复、标准化**流程
2. 项目需要长期生产部署、运维、监控
3. 团队希望逻辑直观，降低后续维护成本
4. 场景：报表、研报、批量内容、企业自动化流程

### 6.2 优先选择 AutoGen
1. 任务**开放、复杂、需要多轮讨论与创意**
2. 流程中存在大量**人工审批、干预、决策**环节
3. 需要 AI 完成代码编写、数据分析、脚本运行
4. 场景：科研、方案策划、软件开发、技术原型验证

### 6.3 优先选择 OpenAI Swarm
1. 仅需**简单线性流转**，步骤少、链路短
2. 基于 OpenAI 生态，追求极简代码、快速上线
3. 轻量化 Demo、内部小工具、客服咨询链路
4. 预算/资源有限，希望降低框架复杂度

### 6.4 混合架构方案（高阶落地）
- 主业务流水线：使用 CrewAI 保证稳定高效
- 复杂决策/创意节点：嵌入 AutoGen 群聊能力
- 外围简单分支流程：使用 Swarm 轻量化接力

---

## 7. 总结
1. **CrewAI = 工业流水线**：稳定、规范、面向规模化生产，是企业落地首选。
2. **AutoGen = 专家会议室**：灵活、自由、擅长复杂问题与人机协同，偏向研究与创意场景。
3. **OpenAI Swarm = 简易传送带**：轻量、快速、上手无门槛，适合小型应用与快速原型。

框架没有绝对优劣，**匹配业务场景**是选型第一原则。三者语法均以 Python 为主，生态互通性较好，可根据项目阶段灵活组合使用。