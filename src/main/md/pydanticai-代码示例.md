# PydanticAI 完整代码示例

> 补充文档，配合 [AI多智能体开发框架对比.md](AI多智能体开发框架对比.md) 阅读

---

## 1. 结构化输出（基础）

```python
# 安装依赖：pip install pydantic-ai
import os
from pydantic_ai import Agent
from pydantic import BaseModel, Field

os.environ["OPENAI_API_KEY"] = "你的OpenAI Key"

class FrameworkAnalysis(BaseModel):
    name: str = Field(description="框架名称")
    summary: str = Field(description="100字以内的核心定位描述")
    strengths: list[str] = Field(description="3个核心优势")
    weaknesses: list[str] = Field(description="2个主要缺点")
    best_for: str = Field(description="最适合的使用场景")
    maturity: str = Field(description="成熟度：production / beta / experimental")

agent: Agent[None, FrameworkAnalysis] = Agent(
    "openai:gpt-4o",
    output_type=FrameworkAnalysis,
    system_prompt="你是资深 AI 工程师，以客观视角分析 AI 框架，输出严格遵循 JSON 格式。"
)

if __name__ == "__main__":
    result = agent.run_sync("分析 CrewAI 框架")
    a = result.output
    print(f"框架：{a.name}")
    print(f"定位：{a.summary}")
    print(f"优势：{', '.join(a.strengths)}")
    print(f"缺点：{', '.join(a.weaknesses)}")
    print(f"适合：{a.best_for}")
    print(f"成熟度：{a.maturity}")
    print(f"Token 消耗：{result.usage()}")
```

---

## 2. 依赖注入 + Tool（进阶）

```python
import asyncio
from dataclasses import dataclass
from pydantic_ai import Agent, RunContext
from pydantic import BaseModel

@dataclass
class ResearchDeps:
    search_api_key: str
    max_results: int = 5

class ResearchReport(BaseModel):
    topic: str
    findings: list[str]
    conclusion: str
    confidence: float   # 0.0 - 1.0

agent: Agent[ResearchDeps, ResearchReport] = Agent(
    "anthropic:claude-opus-4-7",
    deps_type=ResearchDeps,
    output_type=ResearchReport,
    system_prompt="你是资深技术研究员，根据搜索结果输出严谨的研究报告。",
    retries=2
)

@agent.tool
async def search_web(ctx: RunContext[ResearchDeps], query: str) -> str:
    """通过搜索引擎获取最新资讯"""
    # 实际场景替换为真实搜索 API
    limit = ctx.deps.max_results
    return f"[模拟] 搜索「{query}」，返回 {limit} 条结果：趋势A、趋势B、趋势C..."

@agent.tool_plain
def get_framework_stats(framework: str) -> dict:
    """获取框架的 GitHub 统计数据"""
    stats = {
        "CrewAI":    {"stars": 25000, "contributors": 180},
        "AutoGen":   {"stars": 32000, "contributors": 250},
        "MetaGPT":   {"stars": 45000, "contributors": 120},
        "PydanticAI":{"stars": 8000,  "contributors": 60},
    }
    return stats.get(framework, {"stars": 0, "contributors": 0})

async def main():
    deps = ResearchDeps(search_api_key="your-key", max_results=5)
    result = await agent.run("研究 2026 年主流 AI 多智能体框架的市场格局", deps=deps)
    r = result.output
    print(f"主题：{r.topic}")
    for i, f in enumerate(r.findings, 1):
        print(f"  {i}. {f}")
    print(f"结论：{r.conclusion}")
    print(f"可信度：{r.confidence:.0%}")

asyncio.run(main())
```

---

## 3. 多 Agent 编排（手动 Python 调用）

PydanticAI 没有内置多 Agent 框架，通过普通 Python 调用串联多个 Agent：

```python
import asyncio
from pydantic_ai import Agent
from pydantic import BaseModel

# Agent 1：信息采集
class RawData(BaseModel):
    topic: str
    data_points: list[str]

collector = Agent(
    "openai:gpt-4o",
    output_type=RawData,
    system_prompt="你是数据采集专员，搜集指定主题的原始信息，以要点清单输出。"
)

# Agent 2：分析总结
class FinalReport(BaseModel):
    executive_summary: str
    key_insights: list[str]
    recommendations: list[str]

analyst = Agent(
    "openai:gpt-4o",
    output_type=FinalReport,
    system_prompt="你是高级分析师，基于原始数据提炼核心洞察与行动建议。"
)

async def research_pipeline(topic: str) -> FinalReport:
    # Step 1：数据采集
    collect_result = await collector.run(f"采集关于「{topic}」的行业信息")
    raw = collect_result.output

    # Step 2：将采集结果传给分析 Agent
    data_text = "\n".join(f"- {p}" for p in raw.data_points)
    analysis_result = await analyst.run(
        f"主题：{raw.topic}\n\n原始数据：\n{data_text}"
    )
    return analysis_result.output

async def main():
    report = await research_pipeline("AI 多智能体框架 2026 年发展趋势")
    print("=== 执行摘要 ===")
    print(report.executive_summary)
    print("\n=== 核心洞察 ===")
    for insight in report.key_insights:
        print(f"  • {insight}")
    print("\n=== 行动建议 ===")
    for rec in report.recommendations:
        print(f"  → {rec}")

asyncio.run(main())
```

---

## 4. 流式输出

```python
import asyncio
from pydantic_ai import Agent

agent = Agent("openai:gpt-4o", system_prompt="你是一位专业写作助手。")

async def stream_demo():
    print("开始流式输出：\n")
    async with agent.run_stream("撰写一篇关于AI多智能体框架演进的简短文章") as response:
        async for chunk in response.stream_text():
            print(chunk, end="", flush=True)
    print(f"\n\n总 Token 消耗：{response.usage()}")

asyncio.run(stream_demo())
```

---

## 5. TestModel 单元测试

```python
import pytest
from pydantic_ai.models.test import TestModel
from pydantic_ai.models.function import FunctionModel, ModelResponse

# 测试：验证 Agent 结构与 Tool 调用是否正常
def test_agent_structure():
    deps = ResearchDeps(search_api_key="test-key", max_results=3)
    with agent.override(model=TestModel()):
        result = agent.run_sync("测试研究任务", deps=deps)
        assert result.output is not None

# 测试：控制模型响应内容，验证业务逻辑
def test_agent_business_logic():
    def custom_model(messages, info) -> ModelResponse:
        return ModelResponse.from_text(
            '{"topic":"测试主题","findings":["发现1","发现2"],'
            '"conclusion":"测试结论","confidence":0.9}'
        )

    deps = ResearchDeps(search_api_key="test-key")
    with agent.override(model=FunctionModel(custom_model)):
        result = agent.run_sync("任意输入", deps=deps)
        assert result.output.topic == "测试主题"
        assert result.output.confidence == 0.9
        assert len(result.output.findings) == 2
```

---

## 6. 模型切换速查

```python
# 仅需修改 model 参数字符串，其余代码完全不变
models = {
    "gpt-4o":    "openai:gpt-4o",
    "gpt-4o-mini": "openai:gpt-4o-mini",
    "claude":    "anthropic:claude-opus-4-7",
    "sonnet":    "anthropic:claude-sonnet-4-6",
    "gemini":    "google-gla:gemini-2.0-flash",
    "ollama":    "ollama:llama3.1",        # 本地模型，无 API 费用
    "groq":      "groq:llama-3.3-70b-versatile",  # 极速推理
}

agent = Agent(
    models["claude"],
    output_type=ResearchReport,
    system_prompt="..."
)
```
