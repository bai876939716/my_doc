# LLM 网关 · 讲清楚这一篇

> **定位**：本期 = Agent 进程内的**薄封装类**（一个类 + 配置，≈1~1.5 人天），不是独立服务；未来 = 第二个 AI 业务出现时换**开源独立网关**（LiteLLM / OneAPI，一容器），薄壳退化为接入适配层（方案 §3.2 P2）。

---

## 一、能力覆盖总表（LangChain + 薄壳 vs 业界 LLM 网关）

| LLM 网关能力 | 实现方式 | 状态 |
|---|---|---|
| 多供应商统一接入（换型只改配置） | LangChain（ChatOpenAI + OpenAI 兼容协议） | ✅ 白捡 |
| JSON 结构化输出（Schema 注入 + 解析失败自动重试） | LangChain（with_structured_output） | ✅ 白捡 |
| 超时（8s） | LangChain（timeout 参数） | ✅ 白捡 |
| 重试（1 次） | LangChain（max_reries 参数） | ✅ 白捡 |
| Token 用量采集 | LangChain（响应 usage 字段） | ✅ 白捡 |
| 异常分类 + 三级降级出口（超时/不可用/配额满） | 薄壳（try/except 分类抛给状态机） | ✅ 自写 |
| 会话级限流熔断（≤6 次/会话） | 薄壳（Redis 计数） | ✅ 自写 |
| 成本核算 + 调用日志（次数/token/耗时/成败） | 薄壳（llm_call 埋点落库，SQL 出账） | ✅ 自写 |
| 单人单日限额 / 全局日配额 | 同一 Redis 计数套路 | ◐ 放量前补 |
| 主备模型自动切换 | 壳内 try 主 except 切备（≈半天） | ◐ 单模型期无意义 |
| 注入/内容安全检测 | 意图枚举 + 输入当数据（基础版已有） | ◐ 够用，无独立审查模块 |
| 多业务租户接入与管理 | — | ❌ 未实现 |
| 集中式 Key 管理（虚拟 key 分发） | — | ❌ 未实现 |
| 跨业务成本分摊报表 | — | ❌ 未实现 |
| 公司级模型路由策略 | — | ❌ 未实现 |
| 语义缓存 | — | ❌ 未实现（个性化文本命中率≈0） |

**读法**：✅ = 本期 1~1.5 人天全有；◐ = 需要时半天级补上；❌ 全姓「共享」——单业务用不上，等第二个 AI 业务出现换独立开源网关，而不是把薄壳做厚。

---

## 二、实现清单（三档）

**第一档 · 必要且白捡（LangChain 参数级，~2 小时）**：统一客户端换型改配置、超时、重试、JSON mode、异常分类出口。

**第二档 · 必要且自写（各 ~2 小时，合计半天）**：会话级计次熔断（Redis INCR ≤6 次）、llm_call 埋点（模型名/耗时/token/成败 → 落库）。

**第三档 · 明确不做（过度设计）**：多供应商路由、语义缓存、流式透传（联合抽取整体返回即可）、租户计费、prompt AB 平台。

---

## 三、LangChain 对接（三步）

```
pip install langchain langchain-openai    # 注意：ChatOpenAI 在 langchain-openai 包
```

```python
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

class Extraction(BaseModel):            # 联合抽取输出结构，定义一次
    intent: str
    slots: dict
    category_candidates: list
    clarify: str | None

llm = ChatOpenAI(
    base_url=cfg.base_url,              # 换型只改配置
    api_key=cfg.api_key,
    model=cfg.model,
    timeout=8,
    max_retries=1,
)
extractor = llm.with_structured_output(Extraction)   # Schema 注入+解析+失败重试一行搞定
result = extractor.invoke(prompt_text)               # 返回 Extraction 对象，字段直接点
```

- 与 FastAPI 整合：lifespan 启动建网关单例，路由依赖注入；异步用 `ainvoke`（SSE 推的是模板话术，不需要 LLM 逐字流式）
- 三家候选模型全走 OpenAI 兼容端点，**换型零改代码**：GLM / Qwen（DashScope 兼容模式）/ DeepSeek——base_url 以各家官网文档为准
- 三个坑：JSON mode 各家严格度不同（所以用 `with_structured_output` 不用裸 response_format，且壳里仍留 schema 兜底）；`timeout` 单位是秒；requirements 钉死版本

---

## 四、网关类代码形状（全部动作收口在这一个类）

```python
class LLMGateway:
    def __init__(self, cfg):
        self.llm = ChatOpenAI(timeout=8, max_retries=1, **cfg)

    def call(self, sid, messages) -> dict:
        if redis.incr(f"llm:cnt:{sid}") > 6:
            raise QuotaExceeded                # 配额满 → 降级卡片向导
        t0 = now()
        try:
            resp = self.llm.invoke(messages)
        except TimeoutError:
            raise LLMTimeout                   # 已重试1次仍超时 → FALLBACK
        except Exception:
            raise LLMUnavailable               # 不可用 → 卡片向导
        track("llm_call", {"sid": sid, "model": cfg.model, "latency": now()-t0,
                           "tokens": resp.usage_metadata["total_tokens"]})
        return json.loads(resp.content)        # 解析失败按一次失败处理（连败2~3轮降级）
```

**三类异常 → 状态机三条降级路径**（网关的灵魂）：

| 异常 | 降级路径 |
|---|---|
| QuotaExceeded（会话 >6 次） | 降级卡片向导（点选填单，不阻塞报修） |
| LLMTimeout（重试后仍超时） | FALLBACK 全量类别点选 |
| LLMUnavailable（服务不可用） | 卡片向导 / 引导回传统表单 |

---

## 五、演进（对应方案 §3.2 P2）

- **触发**：多模型主备需求，或第 2 个 AI 业务出现（模型预算要分摊）
- **动作**：选型开源（LiteLLM / OneAPI）独立部署一容器——租户管理、集中 Key、跨业务分摊报表全是现成的，**不自研**
- **迁移**：因为所有调用收口在 LLMGateway 一个类，把它换成指向新网关的 HTTP client 即可，引擎与状态机零改动——薄壳天然是新网关的接入适配层
