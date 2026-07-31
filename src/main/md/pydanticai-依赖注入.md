# PydanticAI 依赖注入与类型系统详解

> 补充文档，配合 [AI多智能体开发框架对比.md](AI多智能体开发框架对比.md) 阅读

---

## 1. 泛型 Agent 与类型安全

PydanticAI 的 `Agent` 是一个泛型类：

```python
Agent[DepsType, OutputType]
```

- `DepsType`：运行时注入的依赖类型（无依赖时为 `None`）
- `OutputType`：Agent 最终输出类型（`str` / Pydantic Model / 基础类型）

这两个类型参数保证：
1. IDE（PyCharm、VSCode）在调用时提供完整类型提示
2. 运行时 Pydantic 自动验证 LLM 输出是否符合 `OutputType` Schema
3. 输出不合规时框架自动重试，并将验证错误反馈给 LLM

```python
from pydantic_ai import Agent
from pydantic import BaseModel

class AnalysisOutput(BaseModel):
    title: str
    score: float
    tags: list[str]

# IDE 完全知道 result.output 的类型是 AnalysisOutput
agent: Agent[None, AnalysisOutput] = Agent(
    "openai:gpt-4o",
    output_type=AnalysisOutput,
    system_prompt="输出严格遵循 JSON 格式。",
    retries=2   # 输出验证失败时最多重试 2 次
)
```

---

## 2. 依赖注入系统

### 2.1 定义依赖（推荐用 dataclass）

```python
from dataclasses import dataclass
from typing import Any

@dataclass
class AppDeps:
    db: Any              # 数据库客户端
    cache: Any           # 缓存客户端
    user_id: str         # 当前用户标识
    api_key: str         # 外部 API Key
```

### 2.2 在 Tool 中访问依赖

```python
from pydantic_ai import Agent, RunContext

agent: Agent[AppDeps, str] = Agent(
    "anthropic:claude-opus-4-7",
    deps_type=AppDeps,
)

# 需要依赖的工具——第一个参数必须是 RunContext
@agent.tool
async def fetch_user_history(
    ctx: RunContext[AppDeps],
    limit: int = 10
) -> list[dict]:
    """获取当前用户的操作历史"""
    return await ctx.deps.db.query(
        "SELECT * FROM history WHERE user_id = ? LIMIT ?",
        [ctx.deps.user_id, limit]
    )

# 纯函数工具，不需要依赖时使用 tool_plain
@agent.tool_plain
def calculate_stats(values: list[float]) -> dict:
    """纯计算，无需访问外部服务"""
    return {"mean": sum(values) / len(values), "count": len(values)}
```

### 2.3 运行时传入依赖

```python
deps = AppDeps(
    db=db_client,
    cache=redis_client,
    user_id="user_123",
    api_key="xxx"
)

# 同步
result = agent.run_sync("分析用户行为", deps=deps)

# 异步
result = await agent.run("分析用户行为", deps=deps)

print(result.output)   # 类型安全的输出
```

### 2.4 动态 System Prompt（依赖注入到 prompt）

```python
@agent.system_prompt
def build_prompt(ctx: RunContext[AppDeps]) -> str:
    return f"""你是专业数据分析助手。
当前用户 ID：{ctx.deps.user_id}
请基于该用户的历史数据提供个性化分析。"""
```

---

## 3. Tool 函数签名规范

Tool 的参数类型自动推断为 JSON Schema，供 LLM 理解调用。`pydantic.Field` 可补充描述：

```python
from pydantic import Field

@agent.tool_plain
def search_docs(
    query: str = Field(description="搜索关键词"),
    limit: int = Field(default=10, ge=1, le=100, description="返回结果数量上限"),
    category: str | None = Field(default=None, description="过滤分类，None 表示不过滤")
) -> list[str]:
    """在文档库中搜索相关内容"""
    ...
```

函数的 **docstring** 作为工具描述传递给 LLM，写清楚工具用途能显著提升调用准确率。

---

## 4. 输出验证与自动重试

当 LLM 输出不符合 `output_type` Schema 时，PydanticAI 自动触发重试流程：

```
LLM 输出 JSON
    ↓
Pydantic 验证
    ↓ 失败
框架将错误信息作为 user message 反馈给 LLM
（"你的输出缺少 score 字段，请重新输出"）
    ↓
LLM 重新生成
    ↓
再次验证
    ↓ 达到 retries 上限仍失败
抛出 UnexpectedModelBehavior 异常
```

```python
agent = Agent(
    "openai:gpt-4o",
    output_type=MyModel,
    retries=3   # 最多重试 3 次（默认 1）
)
```

---

## 5. 测试模式

### 5.1 TestModel（无 API 调用）

```python
from pydantic_ai.models.test import TestModel

with agent.override(model=TestModel()):
    result = agent.run_sync("任意输入", deps=mock_deps)
    # TestModel 返回可预测的默认响应，验证 Agent 结构是否正确
    assert result.output is not None
```

### 5.2 FunctionModel（自定义响应内容）

```python
from pydantic_ai.models.function import FunctionModel, ModelResponse

def custom_response(messages, info) -> ModelResponse:
    last = messages[-1].parts[-1].content
    if "错误" in last:
        return ModelResponse.from_text(
            '{"title": "错误报告", "score": 0.1, "tags": ["error"]}'
        )
    return ModelResponse.from_text(
        '{"title": "正常响应", "score": 0.9, "tags": ["ok"]}'
    )

with agent.override(model=FunctionModel(custom_response)):
    result = agent.run_sync("测试错误处理")
    assert result.output.score == 0.9
```

### 5.3 标准测试结构（pytest）

```python
import pytest
from unittest.mock import AsyncMock
from pydantic_ai.models.test import TestModel

@pytest.fixture
def mock_deps():
    return AppDeps(
        db=AsyncMock(),
        cache=AsyncMock(),
        user_id="test_user",
        api_key="test_key"
    )

def test_agent_calls_tool(mock_deps):
    with agent.override(model=TestModel()):
        result = agent.run_sync("测试输入", deps=mock_deps)
        assert result.output is not None
        mock_deps.db.query.assert_called_once()  # 验证工具是否被调用
```

---

## 6. RunContext 完整属性

```python
@agent.tool
async def my_tool(ctx: RunContext[AppDeps]) -> str:
    ctx.deps          # 注入的依赖对象（AppDeps 实例）
    ctx.usage         # 当前 Token 用量统计
    ctx.messages      # 完整历史消息列表
    ctx.tool_name     # 当前工具的名称
    ctx.retry         # 当前是第几次重试（0 开始）
    ctx.model         # 当前使用的模型信息
    ...
```
