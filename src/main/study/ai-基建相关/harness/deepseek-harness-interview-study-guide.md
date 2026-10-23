# DeepSeek Harness 开发岗：面试学习指南

> 面向 Harness / Agent Runtime 开发岗位。本文以 DeepSeek Harness 当前公开的 Developer Preview 文档与源码约定为基准；API 仍可能破坏性变更，复习或动手时请固定 commit / release 并以本地类型定义为准。

## 0. 先建立正确的心智模型

**模型负责推理与生成；Harness 负责让模型在真实环境中持续、可控、可观测地工作。**

DeepSeek Harness（`dsh`）不是“一个内置工具集的聊天应用”，而是一个由 Cordis 驱动的 Agent Harness：模型适配、工具、会话日志、Agent Loop、沙箱、存储、UI 都以插件形式组成。这意味着面试中不要把“改核心循环”当成默认解；优先回答“找到 capability seam（能力缝），替换 Provider 或在事件扩展点挂载插件”。

```text
用户 / 外部事件
       │
       ▼
 Agent Turn（0..n Step） ──► LLM provider
       │                         │
       │                         ▼
       └── Tool registry ◄── tool call / result
                 │
                 ▼
     shell / fs / sandbox / MCP / subagent 等 Provider

所有模型可见输入与关键过程 ──► append-only session log ──► 回放、分叉、审计
```

一句话自我介绍可这样组织：

> 我理解 Harness 的核心不是多塞工具，而是把模型、上下文、工具执行、权限、持久化和观测做成可替换的运行时契约；我会优先通过 Provider/Consumer seam 和 typed event 扩展行为，并用可回放日志和评测验证改动是否真的提升任务成功率。

## 1. 硬性技术底座

### 1.1 TypeScript / Node.js / pnpm

需要达到“能在 ESM monorepo 中写可维护插件”的程度。

重点清单：

- TypeScript：泛型、联合类型与 discriminated union、`async/await`、`AbortSignal`、模块声明合并（declaration merging）、类型/值空间的区别。
- Node.js：事件循环、stream、子进程、环境变量、错误处理、超时与取消；理解为什么 IO 不能阻塞 event loop。
- ESM：`import/export`、`type: module`、CJS/ESM 互操作风险。Harness 源码规范为 ESM everywhere。
- pnpm：workspace、filter、lockfile、peer dependency；能定位“本地插件无法解析依赖”的 workspace / module-resolution 问题。

Harness 当前仓库 `package.json` 声明 Node `^22.19.0 || >=24.0.0`、pnpm `11.7.0`；版本会变化，面试回答时说“以仓库 engines/packageManager 为准”，不要死背版本。

### 1.2 声明合并：为什么是 Harness 插件能力的一部分

Cordis 的 `Context` 需要同时具备运行时能力和编译期类型。运行时 `new Service(ctx, 'metrics')` 把服务挂到上下文；编译期用声明合并让 `ctx.metrics` 有类型。**声明合并不注册服务，它只影响 TypeScript。**

```ts
import { Service, type Context } from '@deepseek-ai/cordis'

declare module '@deepseek-ai/cordis' {
  interface Context {
    metrics: MetricsService
  }
}

export default class MetricsService extends Service {
  constructor(ctx: Context) { super(ctx, 'metrics') }
  record(name: string, value: number) { /* write metric */ }
}
```

面试追问：只写 `declare module` 有什么问题？

答：类型检查会通过，但运行时没有 Provider，消费者仍可能拿不到服务；类型扩展与真实注册必须配套。

## 2. Cordis：一切皆插件的运行时

### 2.1 Context、Service、inject

- `Context (ctx)`：插件协作的容器，也是注册 effect、事件、服务的入口。
- `Service`：一个插件提供给其他插件的命名能力，如 `ctx.tools`、`ctx.llm`、`ctx.agents`。
- `inject`：声明插件的必需依赖。依赖未就绪时插件不执行；Provider 卸载时，依赖它的插件会自动 dispose，Provider 恢复后再加载。

```ts
import type { Context } from '@deepseek-ai/cordis'

export const inject = ['tools']

export function apply(ctx: Context) {
  // 到这里 ctx.tools 已经就绪
  ctx.tools.register(/* tool definition */)
}
```

### 2.2 Effect / disposer：生命周期题的标准答案

插件挂监听器、注册工具、启动 timer、打开 socket、订阅流，都会形成资源。正确做法是让这些注册进入 Cordis 管理的 effect 生命周期：`ctx.on()`、`ctx.effect()`，或保存并在 effect 中调用 registry 返回的 disposer。插件卸载时由框架逆序清理，避免重复监听、悬挂定时器或引用失效服务。

```ts
export function apply(ctx: Context) {
  ctx.on('tools/post-execute', async (next, payload) => {
    const result = await next()
    // 记录审计；不要修改不属于本插件的全局状态
    return result
  })

  ctx.effect(() => {
    const timer = setInterval(flushMetrics, 10_000)
    return () => clearInterval(timer)
  })
}
```

面试可说：**“注册 API 返回 disposer 并不等于万事大吉；关键是 disposer 必须被挂在 plugin 的 effect scope，才能在 HMR、配置变更、依赖消失时可靠执行。”**

### 2.3 配置组合与隔离

`cordis.yml` 将插件组成树。组可以 isolate 某项服务，令不同 preset / agent 使用同一服务接口的独立实例。例如两个 agent group 都依赖 `ctx.shell`，却可以有不同 shell backend 与 timeout。隔离边界设计不当会造成：会话串扰、权限泄漏、同一注册被多个 agent 看见。

## 3. Agent Loop：必须能画清的一次运行

### 3.1 Turn 与 Step

- **Turn**：从接收输入到“不再欠用户/模型任何工作”的完整回合，可包含零到多个 Step。
- **Step**：一次模型请求，加上该响应发起的工具调用与结果处理。

典型链路：

1. `turn/start`：从 inbox 取下一批输入，组装 prompt section 和 tool schema。
2. `agent/pre-step`：策略可接收、改写或拒绝本 step 输入。
3. `step/start` 后，经 `agent/request` / `prepareCall` 确定实际模型路由。
4. 记录系统消息、用户消息和请求上下文，冻结本次模型 history；调用模型并流式接收响应。
5. 对每个 tool call：`tools/pre-execute → tools/execute → tools/post-execute → tool/result`。
6. `step/end`；若工具结果或新输入要求继续，开始下一个 Step；否则 `agent/turn-stopping → turn/end`。

关键认知：**tool result 不是 UI 文本，而是下一次推理的状态输入；session log 不是普通 debug log，而是模型可见上下文和可回放运行的证据。**

### 3.2 Context、KV Cache、reasoning 的工程含义

- 上下文预算是有限资源：工具描述、冗余结果、无关历史都会降低有效信号并增加成本/时延。
- 缓存命中通常依赖稳定的 prompt 前缀；频繁改 system prompt、工具清单顺序或历史拼装方式，会损失 KV cache 命中。这里应测量，而非凭感觉优化。
- reasoning / planning 的输出不等于可靠执行。Harness 要做的是把计划转成可验证的工具动作：参数 schema、权限门控、幂等性、超时、重试与可观测轨迹。
- 上下文压缩不是“总结一下”即可：要保留任务约束、已执行动作、关键证据、未决假设和恢复所需状态，并评测压缩后成功率。

## 4. Capability seam：替换而不侵入

一个完整 seam 有三种角色：

| 角色 | 职责 | 示例 |
| --- | --- | --- |
| Service Definition | 定义稳定接口/契约 | `ctx.shell` 的执行请求、结果、取消语义 |
| Service Provider | 实现接口 | 本地 subprocess、SSH、远端 sandbox |
| Consumer | 依赖接口完成用户可见能力 | 模型可调用的 `bash` tool |

**题：把默认 Shell 换成远端沙箱，要改哪里？**

答：为 `ctx.shell`（必要时连同 fs/subprocess/terminal 的同一 execution world）实现或配置远端 Provider；`bash` tool 作为 Consumer 仍依赖同一 service contract，不应为每个后端分叉。然后验证工作目录映射、文件可见性、stdin/PTY、信号取消、超时、输出截断、网络/凭据策略、审计身份与错误分类。官方架构明确指出，共享执行世界的 filesystem/subprocess provider 迁到远端 sandbox 时，Bash、PTY、LSP 可随之迁移。

## 5. 事件扩展点：策略应从 Loop 中剥离

### 5.1 常用事件与用法

| 事件 | 适合做什么 | 注意点 |
| --- | --- | --- |
| `agent/pre-step` | 输入过滤、注入上下文、自动压缩、选择策略 | 会影响接受的输入，须保持确定性与可审计 |
| `agent/request` | 路由、请求层 policy、模型调用前修订 | 避免在这里悄悄改变模型可见内容而不记录 |
| `llm/stream` | 流式观测、首 token / token 吞吐 metrics | 不要阻塞 streaming 热路径 |
| `tools/pre-execute` | 参数校验、权限/审批、配额、风险分级 | 对副作用工具采用 default-deny 与显式确认 |
| `tools/post-execute` | 结果脱敏、结构化审计、错误规范化 | 不要伪造成功；保留失败证据 |
| `agent/turn-stopping` | 结束前判断、steer 下一步 | serial；不是 waterfall，不调用 `next()` |

`agent/pre-step`、`agent/request`、`llm/stream` 和 `tools/*` 是 **waterfall**：监听器必须调用 `next()` 才把控制交给后续监听器；不调用就是有意短路。`agent/turn-stopping` 是 **serial**，无 `next()`。这是很容易被面试官用来区分“看过名词”和“理解控制流”的点。

### 5.2 审批插件的设计答案

1. 在 `tools/pre-execute` 获取工具名、规范化参数、请求主体、工作区与风险标签。
2. 依据策略允许、拒绝或请求用户确认；把决定与理由写入可审计事件/日志。
3. 对可写文件、shell、网络、凭据访问分别建策略；不要把“工具名白名单”误当完整安全模型。
4. 处理取消、超时、重复提交；确认 token 要绑定具体参数摘要，避免 TOCTOU（批准 A、执行 B）。
5. 结果在 `post-execute` 做敏感数据脱敏，且保留内部安全审计记录。

## 6. Tool Use 与 MCP

### 6.1 好工具的标准

工具定义至少应有稳定名称、清晰 description、严格输入 JSON Schema，最好还有结构化输出与 output schema。要将“面向模型的描述”当作 API 设计：写清何时使用、不该何时使用、字段含义、边界、错误与成本。

经验法则：一个工具应完成一个可验证动作；输出应优先是短小、结构化、可追踪的事实，不把 MB 的原始日志塞回模型。副作用动作设计幂等键或明确确认；所有外部输入都视为不可信 prompt-injection 载体。

### 6.2 MCP 的定位

MCP 是 Host/Client 与外部 Server 之间标准化暴露工具、资源、提示词等能力的协议；它不是 Agent Loop，也不自动提供权限治理。接入 MCP server 前应明确：server 的可信身份、可执行命令/网络边界、tool schema、最小权限、用户确认、结果隔离/脱敏与故障降级。

官方 MCP 工具规范指出：tool 有 `name`、`description`、`inputSchema`，可选 `outputSchema`；客户端应把来自非可信 server 的 annotations 视为不可信。Harness 的 CLI 参考也特别提醒：MCP server command 是 agent sandbox 之外的可信可执行代码，默认不启用。

## 7. PTC / Code mode：何时用，何时不用

在当前 Harness 文档中，PTC runtime 是可选 seam：它运行一段程序，向 host bindings 调用能力并返回 output / failure / sandbox facts；Web preset 的 Code mode 让模型以 TypeScript 程序编排多步工具操作。

| 更适合 PTC | 更适合标准 Tool Loop |
| --- | --- |
| 多个独立或可程序化组合的调用 | 每一步都需要根据新证据推理 |
| 明确数据流、批处理、并发 fan-out/fan-in | 高不确定性探索、频繁的人类介入 |
| 减少模型—工具往返可能带来收益 | 任务很小，生成/审查代码反而是固定开销 |

面试时不要说“PTC 一定更快”。正确表述是：它可能减少模型请求轮次，但会增加代码生成、sandbox 启动、绑定序列化、调试和安全审查成本。要为它配置 sandbox、deadline、资源限制和能力最小化；应比较端到端成功率、p50/p95 latency、token、工具调用数与安全拒绝率。

## 8. Subagent：并行不是免费午餐

适用：可分解、上下文相对独立、输出接口明确的工作，如“一个子 agent 查代码位置，一个写测试计划，一个复核风险”。

必须设计：

- 任务边界与交付 schema：子 agent 返回结论、证据、修改/命令、置信度，而非一段难解析长文。
- 状态与隔离：独立上下文、工作目录/分支、权限、token/时间预算。
- 调度：并发上限、取消传播、超时、失败重试与去重。
- 汇总：父 agent 验证子结果，不能把子 agent 输出视为可信事实。
- 观测：parent/child 关联 ID、每个 agent 的轨迹、成本和结果质量。

反模式：让多个子 agent 同时写同一文件、给每个子 agent 全量上下文、没有停止条件地递归派生、用自然语言猜测任务是否完成。

## 9. “重度用户品味”：如何诊断 Agent 反常

面试常见问法：“模型老是调用错工具/反复执行/看起来知道但做不对，你怎么定位？”

建议用下面的排查顺序回答：

1. **复现与固定变量**：固定 model、版本、seed（若支持）、系统提示、工具集和 workspace；保存完整 trajectory。
2. **看轨迹而非只看最终回复**：模型看到了什么 prompt、实际拿到哪些 schema、调用参数是什么、工具返回什么、下一 step 是否被正确回注。
3. **分层归因**：
   - 选错工具：description/schema 重叠、工具太多、路由提示不足。
   - 参数错：schema 约束不足、示例缺失、单位/路径语义歧义。
   - 工具成功但任务失败：工具返回了不可行动的长文本、缺少结构化证据或真实副作用。
   - 重复循环：终止条件不清、错误未分类、结果未改变状态、工具无幂等保护。
   - 上下文遗忘：压缩丢失约束、历史截断、prompt 前缀频繁变化。
   - 不安全动作：工具结果/网页 prompt injection，或审批边界绕过。
4. **提出可测假设并做 A/B**：一次只修改一个变量，使用任务集比较成功率、成本、时延、人工介入率和安全事件，而不是凭单例对话判断。

一句成熟的表达：**“我不会先调 prompt；先从 trajectory 证明模型实际接收到的上下文和工具契约，再决定改 schema、编排、提示、provider 还是 policy。”**

## 10. 可在面试中展示的动手项目

### 最小项目：工具审计插件

目标：写一个 Cordis 插件，在 `tools/pre-execute` 与 `tools/post-execute` 记录 trace ID、工具名、耗时、成功/失败分类，并确保卸载后不会继续监听。

验收标准：

- `inject` 声明准确的服务依赖。
- 使用 `ctx.on` / `ctx.effect` 管理生命周期。
- 不记录 secret；参数和结果只保存允许字段或摘要。
- 能给出一条 trace 串起一次 turn 的多个 tool call。

### 进阶项目：可确认的远端 Shell Provider（设计即可）

写一份 2 页设计：service contract、远端任务协议、文件同步/工作目录语义、取消与超时、sandbox policy、审批点、日志关联、故障模型（网络断开、重复请求、远端丢状态）和集成测试矩阵。这个题目能自然展示 TypeScript、分布式系统、安全和 Harness 架构能力。

### 从源码启动（以当前 README 为准）

```bash
git clone https://github.com/deepseek-ai/deepseek-harness.git
cd deepseek-harness
pnpm install
pnpm run build
pnpm dsh web
```

建议额外运行一个最小任务，打开 trajectory / session log，亲自回答：一次 turn 中发生了几步？工具结果以什么形式进入下一请求？禁用一个 provider 后 dependent plugin 如何反应？这些经验比背概念更有说服力。

## 11. 高频面试题与回答骨架

### Q1：为什么 Harness 要“everything is a plugin”？

为了把环境差异和产品策略从 Agent Loop 中解耦。模型、工具、存储、执行环境和 UI 的变化速度不同，插件 + service contract 可以局部替换、配置组合和独立测试。代价是接口设计、生命周期、依赖图和版本兼容要更严谨。

### Q2：工具调用失败后如何处理？

先区分可重试的瞬态错误、参数/权限错误、不可恢复错误和取消；记录结构化错误与因果链。重试要有预算、退避和幂等前提，不能对写操作盲目重试。将足以让模型纠正行为的摘要回注，同时避免泄露敏感内部细节。

### Q3：如何评价一个 Harness 改动好不好？

建立代表性任务集和对照组，指标至少包括 task success、p50/p95 时延、token/成本、tool calls、人工审批率、失败分类、安全事件、轨迹可回放率；再做定性 failure review。单次 demo 成功不代表改动有效。

### Q4：如何处理 Developer Preview 的破坏性变更？

锁定依赖版本/commit，CI 中复现构建；把外部 API 适配封在薄 Provider 层；为插件契约写 compile-time 与行为测试；订阅 release / migration，升级时检查所有 consumer。对 session/schema 做显式版本化和迁移，绝不悄悄改变旧数据解释。

### Q5：你会如何防 prompt injection？

将网页、文件、MCP/tool result 视为不可信数据，不让其直接改写 system-level policy；权限在执行边界独立决策，敏感/副作用操作需要确认；最小权限、来源标记、参数绑定审批、secret 隔离、审计与可回放。模型“看起来遵守”不是安全保证。

## 12. 两周冲刺计划

| 天数 | 重点 | 可交付物 |
| --- | --- | --- |
| 1–2 | TS / Node ESM / pnpm workspace | 写一个 typed event + disposer 的小例子 |
| 3–4 | Cordis tutorial：plugin、effect、service、event | 能解释 `inject` 与声明合并 |
| 5–6 | 跑 Harness，阅读 architecture 与一次 trajectory | 画一张 Turn/Step 流程图 |
| 7–8 | 工具、审批、MCP、安全 | 审批插件伪代码与威胁模型 |
| 9–10 | PTC、shell/sandbox seam、subagent | 远端 shell Provider 设计文档 |
| 11–12 | 评测与故障诊断 | 10 条任务的小型评测表 |
| 13–14 | 模拟面试 | 录 5 分钟架构讲解，复盘术语与证据 |

## 13. 外部资料（优先官方）

### DeepSeek Harness / Cordis

- [DeepSeek Harness GitHub 仓库与从源码运行说明](https://github.com/deepseek-ai/deepseek-harness)
- [官方开发文档首页](https://deepseek-harness.github.io/deepseek-harness/)
- [官方架构：Turn/Step、事件、能力缝](https://deepseek-harness.github.io/deepseek-harness/en/reference/)
- [Cordis 教程：从第一个插件到接入 Harness](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-tutorial/index.md)
- [Service 与依赖注入教程](https://deepseek-harness.github.io/deepseek-harness/en/develop/framework/service)
- [扩展插件 cookbook](https://deepseek-harness.github.io/deepseek-harness/en/reference/cookbook/extension-cookbook)
- [Tool 编写参考](https://deepseek-harness.github.io/deepseek-harness/en/reference/cookbook/adding-a-tool)
- [PTC runtime 参考](https://deepseek-harness.github.io/deepseek-harness/en/reference/subsystems/ptc-runtime)
- [源码协作规范 AGENTS.md（含 ESM、effect、waterfall、日志原则）](https://github.com/deepseek-ai/deepseek-harness/blob/master/AGENTS.md)
- [当前 Node / pnpm engines](https://github.com/deepseek-ai/deepseek-harness/blob/master/package.json)

### 通用但直接相关

- [TypeScript Handbook：Declaration Merging](https://www.typescriptlang.org/docs/handbook/declaration-merging.html)
- [Node.js：AbortController / AbortSignal](https://nodejs.org/api/globals.html#class-abortcontroller)
- [pnpm Workspaces](https://pnpm.io/workspaces)
- [MCP 官方规范：Tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [MCP 官方规范仓库](https://github.com/modelcontextprotocol/modelcontextprotocol)

## 14. 最后检查表

面试前，请确保你能不看资料回答：

- Turn 与 Step 有何区别？工具结果何时、以什么身份进入下一次模型调用？
- 一个 Cordis 服务从类型、Provider 到 Consumer 如何闭环？依赖消失会发生什么？
- waterfall 与 serial 的控制流差异？漏掉 `next()` 的后果？
- 为什么远端沙箱应该替换 provider，而不是复制一份 Bash tool？
- PTC 的收益、固定成本与隔离需求分别是什么？
- 如何用 trajectory 区分 prompt、schema、上下文、tool result、provider 和 policy 问题？
- 如何证明你的优化不是偶然 demo，而是可复现提升？

如果这七题都能结合一次亲手跑出的轨迹和一个小插件回答，你已经具备相当扎实的 Harness 开发岗面试基础。
