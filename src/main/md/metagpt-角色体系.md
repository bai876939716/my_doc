# MetaGPT 角色体系详解

> 补充文档，配合 [AI多智能体开发框架对比.md](AI多智能体开发框架对比.md) 阅读

---

## 1. 整体架构：共享消息池模型

MetaGPT 采用**黑板（Blackboard）架构**，所有角色共享一个消息池（Shared Message Pool）：

```
用户需求
    ↓
ProductManager ──→ [发布 Message: PRD]
                           ↓（Architect 订阅 WritePRD）
                   Architect ──→ [发布 Message: SystemDesign]
                                        ↓（ProjectManager 订阅 WriteDesign）
                                 ProjectManager ──→ [发布 Message: Tasks]
                                                         ↓（Engineer 订阅 WriteTasks）
                                                  Engineer × N ──→ [发布 Message: Code]
                                                                         ↓（QaEngineer 订阅 WriteCode）
                                                                 QaEngineer ──→ [发布 Message: TestReport]
```

每个角色通过 `_watch` 声明感兴趣的消息类型，消息池中出现对应消息时自动触发执行。角色之间完全解耦，不直接通信。

---

## 2. 内置标准角色详解

### 2.1 ProductManager（产品经理）
- **触发条件**：接收到用户的初始需求消息（`UserRequirement`）
- **核心 Action**：`WritePRD`
- **输出**：Markdown 格式 PRD，包含功能描述、用户故事、技术约束、竞品分析

### 2.2 Architect（架构师）
- **触发条件**：接收到 `WritePRD` 产出的 PRD 消息
- **核心 Action**：`WriteDesign`
- **输出**：系统设计文档（Mermaid 类图、数据结构、接口定义、技术选型）

### 2.3 ProjectManager（项目经理）
- **触发条件**：接收到 `WriteDesign` 产出的系统设计消息
- **核心 Action**：`WriteTasks`
- **输出**：JSON 格式任务清单，含模块划分与文件路径规划

### 2.4 Engineer（工程师）
- **触发条件**：接收到 `WriteTasks` 产出的任务消息
- **核心 Action**：`WriteCode`（支持多并行，由 `n_borg` 参数控制）
- **输出**：完整源代码文件
- **关键参数**：

| 参数 | 默认值 | 说明 |
| ---- | ---- | ---- |
| `n_borg` | 1 | 并行工程师数量，提升编码吞吐 |
| `use_code_review` | False | 是否对生成代码进行自我审查 |

### 2.5 QaEngineer（QA 工程师）
- **触发条件**：接收到 `WriteCode` 产出的代码消息
- **核心 Action**：`WriteTest` → `RunCode` → `DebugError`
- **输出**：测试代码与测试执行报告
- **特性**：可自动执行代码、捕获 stderr、触发 Debug 修复循环（最多 3 轮）

---

## 3. Action（动作）系统

Action 是角色执行的原子操作单元，每个 Action 封装：
- 一次 LLM Prompt 构造与调用（通过 `_aask` 方法）
- 输出解析与结构化处理逻辑

### 内置主要 Action 一览

| Action | 所属角色 | 说明 |
| ---- | ---- | ---- |
| `WritePRD` | ProductManager | 根据需求生成完整 PRD 文档 |
| `WriteDesign` | Architect | 根据 PRD 生成系统设计与类图 |
| `WriteTasks` | ProjectManager | 将系统设计拆解为开发任务清单 |
| `WriteCode` | Engineer | 根据任务编写代码 |
| `WriteCodeReview` | Engineer | 对已生成代码进行同行审查 |
| `WriteTest` | QaEngineer | 根据代码编写 pytest 测试用例 |
| `RunCode` | QaEngineer | 在沙箱中执行代码并收集输出 |
| `DebugError` | QaEngineer | 根据报错信息自动生成修复代码 |

---

## 4. 自定义角色

继承 `Role` 类，按以下步骤实现：

```python
from metagpt.roles import Role
from metagpt.actions import Action
from metagpt.actions.add_requirement import UserRequirement
from metagpt.schema import Message

# 第一步：自定义 Action
class AnalyzeMarket(Action):
    name: str = "AnalyzeMarket"

    PROMPT_TEMPLATE: str = """
    你是资深市场分析师。请针对以下主题输出结构化市场分析：
    主题：{topic}

    输出格式（Markdown）：
    ## 市场规模
    ## 核心玩家
    ## 技术趋势
    ## 机会与风险
    """

    async def run(self, topic: str) -> str:
        prompt = self.PROMPT_TEMPLATE.format(topic=topic)
        return await self._aask(prompt)


# 第二步：自定义 Role
class MarketAnalyst(Role):
    name: str = "市场分析师"
    profile: str = "MarketAnalyst"
    goal: str = "输出高质量市场分析报告"
    constraints: str = "数据客观，观点有据，格式规范"

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.set_actions([AnalyzeMarket])
        self._watch([UserRequirement])  # 监听用户需求消息

    async def _act(self) -> Message:
        todo = self.rc.todo                    # 当前待执行的 Action
        user_msg = self.get_memories(k=1)[0]  # 获取最近一条消息
        result = await todo.run(user_msg.content)
        return Message(content=result, role=self.profile, cause_by=type(todo))
```

---

## 5. 消息订阅机制

角色通过 `_watch` 声明订阅的消息类型，底层依赖 Action 类型作为消息标识：

```python
from metagpt.actions.add_requirement import UserRequirement
from metagpt.actions import WritePRD, WriteDesign, WriteCode

# 订阅用户原始输入
self._watch([UserRequirement])

# 订阅单个 Action 产出的消息
self._watch([WritePRD])

# 订阅多个 Action 产出（任一满足即触发）
self._watch([WriteCode, WriteCodeReview])
```

消息匹配规则：
- 消息的 `cause_by` 字段与角色订阅的 Action 类型匹配时，角色被激活
- 一个 round 中，所有被激活的角色会依次执行
- `n_round` 参数控制整体运行轮次上限，防止无限循环

---

## 6. 关键配置

通过 `~/.metagpt/config2.yaml` 或环境变量配置：

```yaml
# ~/.metagpt/config2.yaml
llm:
  api_type: "openai"          # openai / anthropic / ollama / azure
  model: "gpt-4o"
  api_key: "你的 API Key"
  base_url: "https://api.openai.com/v1"
  temperature: 0.0            # 建议设为 0，保证代码生成稳定性

# 代码执行沙箱
sandbox:
  use_docker: false           # 生产环境建议开启 Docker 沙箱
  timeout: 30                 # 代码执行超时秒数

# 工作目录（生成的代码存放位置）
workspace:
  path: "./workspace"
```
