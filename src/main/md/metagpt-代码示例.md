# MetaGPT 完整代码示例

> 补充文档，配合 [AI多智能体开发框架对比.md](AI多智能体开发框架对比.md) 阅读

---

## 1. 标准 SoftwareCompany 示例

最常用方式：组建标准软件团队，输入需求自动产出完整项目。

```python
# 安装依赖：pip install metagpt
import asyncio
import os
from metagpt.roles import (
    ProductManager, Architect, ProjectManager, Engineer, QaEngineer
)
from metagpt.team import Team

os.environ["OPENAI_API_KEY"] = "你的OpenAI Key"

async def build_software(idea: str, budget: float = 3.0, rounds: int = 5):
    team = Team()
    team.hire([
        ProductManager(),
        Architect(),
        ProjectManager(),
        Engineer(n_borg=3, use_code_review=True),  # 3 并行 + 代码自审
        QaEngineer(),
    ])
    team.invest(investment=budget)   # Token 预算上限（美元）
    team.run_project(idea)
    await team.run(n_round=rounds)

if __name__ == "__main__":
    asyncio.run(build_software(
        idea="开发一个支持增删改查的命令行 Todo 管理工具，使用 SQLite 存储数据",
        budget=5.0,
        rounds=8
    ))
```

运行完成后，产物存储在 `workspace/` 目录：
```
workspace/
├── docs/
│   ├── prd.md              # 产品需求文档
│   ├── system_design.md    # 系统设计文档（含类图）
│   └── task.md             # 任务清单
└── todo_tool/
    ├── main.py
    ├── database.py
    ├── models.py
    └── tests/
        └── test_main.py
```

---

## 2. 自定义 Action

当内置 Action 不满足需求时，继承 `Action` 实现自定义逻辑：

```python
from metagpt.actions import Action

class WriteIndustryReport(Action):
    """行业分析报告生成 Action"""
    name: str = "WriteIndustryReport"

    PROMPT_TEMPLATE: str = """
    你是一位资深行业分析师。请根据以下需求生成一份结构化行业分析报告：

    需求：{requirement}

    报告必须包含以下章节（Markdown 格式）：
    ## 行业概述（200字内）
    ## 核心竞争格局（列举3-5个关键玩家）
    ## 技术趋势（3个要点）
    ## 市场机会与风险
    ## 结论与建议
    """

    async def run(self, requirement: str) -> str:
        prompt = self.PROMPT_TEMPLATE.format(requirement=requirement)
        return await self._aask(prompt)


class CollectData(Action):
    """原始数据采集 Action"""
    name: str = "CollectData"

    async def run(self, topic: str) -> str:
        prompt = f"搜集关于「{topic}」的行业数据，以要点清单形式输出（每条50字以内，共10条）。"
        return await self._aask(prompt)
```

---

## 3. 自定义 Role + 多角色协作（完整示例）

```python
import asyncio
from metagpt.roles import Role
from metagpt.actions import Action
from metagpt.actions.add_requirement import UserRequirement
from metagpt.schema import Message
from metagpt.team import Team
from metagpt.logs import logger


# ── Actions ──────────────────────────────────────────

class CollectData(Action):
    name: str = "CollectData"

    async def run(self, topic: str) -> str:
        prompt = f"搜集关于「{topic}」的行业数据，以要点清单输出（共10条，每条50字内）。"
        return await self._aask(prompt)


class WriteIndustryReport(Action):
    name: str = "WriteIndustryReport"

    async def run(self, data: str) -> str:
        prompt = f"基于以下数据，撰写一份500字以内的行业分析报告：\n\n{data}"
        return await self._aask(prompt)


# ── Roles ────────────────────────────────────────────

class DataCollector(Role):
    name: str = "数据采集员"
    profile: str = "DataCollector"
    goal: str = "采集高质量的行业原始数据"

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.set_actions([CollectData])
        self._watch([UserRequirement])   # 监听用户需求

    async def _act(self) -> Message:
        todo = self.rc.todo
        user_msg = self.get_memories(k=1)[0]
        data = await todo.run(user_msg.content)
        logger.info(f"[{self.name}] 数据采集完成，共 {len(data)} 字")
        return Message(content=data, role=self.profile, cause_by=type(todo))


class ReportWriter(Role):
    name: str = "报告撰写员"
    profile: str = "ReportWriter"
    goal: str = "将数据转化为高质量行业报告"

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.set_actions([WriteIndustryReport])
        self._watch([CollectData])       # 监听数据采集完成的消息

    async def _act(self) -> Message:
        todo = self.rc.todo
        data_msg = self.get_memories(k=1)[0]
        report = await todo.run(data_msg.content)
        logger.info(f"[{self.name}] 报告撰写完成")
        return Message(content=report, role=self.profile, cause_by=type(todo))


# ── 运行入口 ──────────────────────────────────────────

async def main():
    team = Team()
    team.hire([DataCollector(), ReportWriter()])
    team.invest(investment=1.0)
    team.run_project("2026年AI多智能体框架市场竞争格局")
    await team.run(n_round=3)

if __name__ == "__main__":
    asyncio.run(main())
```

---

## 4. 增量开发（在已有项目上继续迭代）

```python
import asyncio
from metagpt.roles import ProductManager, Architect, ProjectManager, Engineer, QaEngineer
from metagpt.team import Team

async def continue_project(idea: str, project_path: str):
    team = Team()
    team.hire([
        ProductManager(),
        Architect(),
        ProjectManager(),
        Engineer(n_borg=2, use_code_review=True),
        QaEngineer(),
    ])
    team.invest(investment=2.0)
    # 指定已有项目路径，MetaGPT 在已有代码基础上新增功能
    team.run_project(idea, project_path=project_path)
    await team.run(n_round=5)

if __name__ == "__main__":
    asyncio.run(continue_project(
        idea="在现有 Todo 工具基础上，新增标签分类和优先级排序功能",
        project_path="./workspace/todo_tool"
    ))
```

---

## 5. 关键参数速查

```python
# Team 参数
team.hire([
    Engineer(
        n_borg=3,              # 并行工程师数（默认 1）
        use_code_review=True,  # 启用代码自我审查（默认 False）
    )
])
team.invest(investment=5.0)    # Token 预算上限（美元），超出后停止

# Team.run() 参数
await team.run(
    n_round=10,                # 最大运行轮次（建议 5-15）
)

# team.run_project() 参数
team.run_project(
    idea="需求描述",
    project_path="./existing_project"  # 增量开发时指定已有目录
)
```
