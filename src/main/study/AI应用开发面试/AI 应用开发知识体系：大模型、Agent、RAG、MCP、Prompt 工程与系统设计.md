---
title: AI 应用开发知识体系：大模型、Agent、RAG、MCP、Prompt 工程与系统设计
description: AI 应用开发面试与学习路线，面向后端开发者梳理大模型调用、Agent、RAG、Skills、MCP、Prompt 工程、向量数据库、评测和系统设计。
category: AI
tag:
  - AI
  - 大模型
  - AI 应用开发
  - 后端面试
icon: mdi:robot-outline
sitemap:
  changefreq: weekly
  priority: 1
head:
  - - meta
    - name: keywords
      content: AI应用开发,AI应用开发面试,AI工程师面试,大模型,大模型面试,LLM,LLM面试,Agent,Agent面试,RAG,RAG面试,MCP,Prompt工程,向量数据库,AI系统设计,AI编程面试
  - - meta
    - property: og:title
      content: AI 应用开发知识体系：大模型、Agent、RAG、MCP、Prompt 工程与系统设计
  - - meta
    - property: og:description
      content: 从大模型调用、Agent、RAG、MCP、Prompt 工程到评测和系统设计，梳理后端开发者进入 AI 应用开发需要补齐的关键知识。
---

<!-- @include: @small-advertisement.snippet.md -->

做 AI 应用不是把 Prompt 塞进接口就结束了。真到项目里，马上会遇到上下文长度、结构化输出、RAG 召回、工具权限、评测回归、成本和稳定性这些问题。

这些问题没法各解各的。大模型基础、Agent、RAG、工具调用、系统设计必须连起来理解——只懂调用 API，到了架构评审会卡住；只熟 RAG 论文，到了知识库维护还是不知道怎么处理增量更新和版本去重。

如果时间有限，先看 [AI 应用开发面试指南](./interview-questions/01%20-%202026%20大模型面试题%20：%20Agent%20面试题%20：%20RAG%20面试题%20：%20AI%20应用开发面试指南（含答案与图解）.md)，把大模型、Agent、RAG、Skills、MCP 和 AI 系统设计里最容易被追问的问题过一遍；如果想补得扎实一些，再按下面的阅读顺序推进。

这应该是当前最全面系统的讲解，每一篇都花费了大量时间完善和优化，每篇文章都画了大量配图辅助理解：

![AIGuide 内容概览，大量配图](https://oss.javaguide.cn/github/aiguide/aiguide-overview.png)

本专栏所属 AIGuide 项目，对标 JavaGuide 质量（免费开源，欢迎 Star 鼓励）：

- **项目地址**：[https://github.com/Snailclimb/AIGuide](https://github.com/Snailclimb/AIGuide)
- **在线阅读**：[https://javaguide.cn/ai-coding/](https://javaguide.cn/ai-coding/)

发布之后，也是收到了很多读者朋友的好评和推荐。非常感谢，一定会持续用心维护！

![AIGuide 收到了很多读者朋友的好评和推荐](https://oss.javaguide.cn/github/aiguide/ai-guide-received-many-positive-reviews-and-recommendations-from-readers.png)

## 适合谁看

- 正在从后端开发转向 AI 应用开发，想补齐大模型、Agent、RAG 和系统设计主线的工程师。
- 准备 AI 工程师、AI 应用开发、后端转 AI 相关岗位面试的同学。
- 做过 Prompt Demo，但对模型调用链路、结构化输出、RAG 检索优化和评测闭环还不够熟的开发者。
- 想把 MCP、Function Calling、Tool Calling、向量数据库、模型网关这些概念放到真实项目里理解的读者。
- 已经在项目中接入大模型，但开始遇到稳定性、成本、安全治理和质量回归问题的团队成员。

## 几个容易踩坑的地方

大模型真不能只当成一个黑盒 API 来调。Token 被截断、采样参数一变输出就飘、说好返回 JSON 结果还是乱了，这些问题靠 Prompt 很难彻底兜住。你在提示词里加一句“请严格按照 JSON 输出”，只能算第一层约束，真正上线时还是得在调用链路里做格式校验、重试、兜底和异常处理。

Agent 也不是能自动调工具就完事了。真正难的是 Memory 和 Context Engineering。上下文没管好，Agent 跑几轮之后就容易偏题，前面说过什么、当前任务做到哪一步、哪些工具结果还能用，全都可能乱掉。长任务里更明显，有时候它不是不会做，而是循环几次之后自己把自己绕进去了，一直跑到 token 快耗完才停。

RAG 答非所问，很多时候也别急着怪模型。大部分问题其实出在召回阶段：Chunk 切得太粗、Query 没改写、关键词检索和向量检索没结合、重排没做好。这个时候一项一项排查召回链路，往往比直接换一个更贵的模型有用。

MCP、Function Calling、Tool Calling 这些东西，解决的是工具怎么接进来的问题。协议统一之后，接工具确实方便了，但真到生产环境，麻烦的地方反而在后面：谁能调用这个工具、能操作哪些数据、调用记录怎么审计、失败了怎么回滚。这些如果没设计好，协议再标准也不够用。

AI 应用一旦上线，稳定性、可观测、成本控制、质量回归这些问题都会冒出来。Demo 阶段通常感受不到，因为调用量小、场景也干净。等真正接到业务流量里，第一次做生产级 AI 应用的团队，基本都会被这些问题教育一次。

## 建议阅读顺序

1. [AI 应用开发面试指南](./interview-questions/01%20-%202026%20大模型面试题%20：%20Agent%20面试题%20：%20RAG%20面试题%20：%20AI%20应用开发面试指南（含答案与图解）.md)：先建立高频问题清单，知道面试和项目复盘最常被追问哪些点。
2. [万字拆解 LLM 运行机制](./llm-basis/02%20-%20LLM%20运行机制：Token、上下文窗口与采样参数怎么影响输出.md)、[大模型 API 调用工程实践](./llm-basis/03%20-%20大模型%20API%20调用工程实践：流式输出、重试、限流与结构化返回.md)：理解模型调用链路、上下文和结构化返回。
3. [一文搞懂 AI Agent 核心概念](./agent/04%20-%20AI%20Agent%20核心概念：Agent%20Loop、Plan-and-Execute、A2A、Agentic%20Workflows、Tools%20注册.md)、[大模型提示词工程实践指南](./agent/05%20-%20大模型提示词工程（Prompt%20Engineering）是什么？提示词技巧有哪些？.md)、[上下文工程实战指南](./agent/06%20-%20上下文工程%28Context%20Engineering%29%20是什么？和%20Prompt%20Engineering%20有什么区别？.md)：建立 Agent 和 Prompt/Context 的基础认知。
4. [万字详解 RAG 基础概念](./rag/07%20-%20万字详解%20RAG%20基础概念.md)、[RAG 文档处理与切分策略](./rag/08%20-%20RAG%20文档处理与切分策略：从解析、清洗、Chunking%20到多模态内容处理.md)、[万字详解 RAG 检索优化](./rag/09%20-%20万字详解%20RAG%20优化：从召回、重排到上下文工程的系统调优.md)：补齐企业知识库问答主线。
5. [AI 应用系统设计](./system-design/10%20-%20AI%20应用系统设计：从%20Prompt%20Demo%20到生产级架构.md)、[大模型网关详解](./system-design/11%20-%20大模型网关详解：多模型路由、fallback、限流与成本控制.md)、[AI 应用评测体系](./llm-basis/12%20-%20AI%20应用评测体系：从%20Golden%20Set%20构建到线上灰度闭环.md)：把 Demo 放进真实后端系统里，补齐网关、评测和治理。

## 核心文章

### 面试与复习路线

- [AI 应用开发面试题专题](./interview-questions/13%20-%20AI%20应用开发面试题专题.md)：按大模型基础、AI Agent、RAG 和 AI 系统设计组织复习路线。
- [AI 应用开发面试指南](./interview-questions/01%20-%202026%20大模型面试题%20：%20Agent%20面试题%20：%20RAG%20面试题%20：%20AI%20应用开发面试指南（含答案与图解）.md)：把 AI 应用开发常见追问放到一条复习路线里，适合先看。
- [大模型基础面试题总结](./interview-questions/14%20-%20大模型基础面试题总结.md)：覆盖 Token、上下文窗口、采样参数、API 调用、结构化输出和评测体系。
- [AI Agent 面试题总结](./interview-questions/15%20-%20AI%20Agent%20面试题总结.md)：覆盖 Agent Loop、Memory、Prompt、Context、MCP、Skills、Harness Engineering 和工作流。
- [RAG 面试题总结](./interview-questions/16%20-%20RAG%20面试题总结.md)：覆盖 RAG 基础、向量数据库、文档处理、检索优化、GraphRAG、知识库更新和评测。
- [AI 系统设计面试题总结](./interview-questions/17%20-%20AI%20系统设计面试题总结.md)：覆盖生产级 AI 应用架构、模型网关、可观测、评测、安全治理和实时语音 Agent。

### 大模型基础

- [大模型基础专题](./llm-basis/18%20-%20大模型基础专题：运行机制、API%20调用、结构化输出与评测.md)：从模型运行机制、API 调用、结构化输出到 AI 应用评测，先把调用链路看明白。
- [万字拆解 LLM 运行机制](./llm-basis/02%20-%20LLM%20运行机制：Token、上下文窗口与采样参数怎么影响输出.md)：把 Token、上下文窗口、Temperature 等概念还原为清晰、可控的工程参数。
- [大模型 API 调用工程实践](./llm-basis/03%20-%20大模型%20API%20调用工程实践：流式输出、重试、限流与结构化返回.md)：拆解 Prompt 组装、模型网关、流式响应、重试限流和结构化返回。
- [大模型结构化输出详解](./llm-basis/19%20-%20大模型结构化输出：从%20JSON%20契约到%20Function%20Calling%20落地.md)：讲清 JSON Schema、Function Calling、Tool Calling 与 MCP 的底层链路。
- [AI 应用评测体系](./llm-basis/12%20-%20AI%20应用评测体系：从%20Golden%20Set%20构建到线上灰度闭环.md)：覆盖 Golden Set、LLM-as-Judge、RAG/Agent 指标、Trace 回放和线上灰度闭环。

### AI Agent

- [AI Agent 专题](./agent/20%20-%20AI%20Agent%20专题：Agent%20Loop、Memory、Prompt、Context、MCP%20与%20Skills.md)：从 Agent 基础概念、Memory、Prompt、Context 到 MCP、Skills 和 Harness Engineering。
- [一文搞懂 AI Agent 核心概念](./agent/04%20-%20AI%20Agent%20核心概念：Agent%20Loop、Plan-and-Execute、A2A、Agentic%20Workflows、Tools%20注册.md)：理解 Agent 和传统编程、Workflow 的区别，以及 Agent Loop、Tools 注册等核心概念。
- [AI Agent 记忆系统](./agent/21%20-%20AI%20Agent%20记忆系统：短期记忆、长期记忆与记忆演化机制.md)：深入理解短期记忆、长期记忆、记忆生命周期和生产级优化策略。
- [大模型提示词工程实践指南](./agent/05%20-%20大模型提示词工程（Prompt%20Engineering）是什么？提示词技巧有哪些？.md)：掌握 Prompt 四要素、常见技巧和 Prompt 注入防护。
- [上下文工程实战指南](./agent/06%20-%20上下文工程%28Context%20Engineering%29%20是什么？和%20Prompt%20Engineering%20有什么区别？.md)：理解静态规则编排、动态信息挂载、Token 预算降级和上下文持久化。
- [万字拆解 MCP 协议](./agent/22%20-%20什么是%20Model%20Context%20Protocol%20%28MCP%29？和%20Function%20Calling、Agent%20什么关系？.md)：理解 MCP 的分层架构、核心能力和 MCP Server 生产实践。
- [万字详解 Agent Skills](./agent/23%20-%20Agent%20Skills%20是什么？和%20Prompt、MCP%20到底差在哪？.md)：理解 Skills 与 Prompt、MCP、Function Calling 的本质区别。
- [一文搞懂 Harness Engineering](./agent/24%20-%20一文搞懂%20Harness%20Engineering：六层架构、上下文管理与一线团队实战.md)：拆解 Model + Harness 的工程化架构和一线团队实践。
- [AI 工作流中的 Workflow、Graph 与 Loop](./agent/25%20-%20AI%20工作流中的%20Workflow、Graph%20与%20Loop：从概念到实现.md)：理解 AI 工作流的节点、边、状态、安全边界和实现方式。

### RAG 检索增强生成

- [RAG 专题](./rag/26%20-%20RAG%20专题：文档处理、向量数据库、GraphRAG、检索优化与知识库更新.md)：围绕企业知识库问答，梳理文档处理、向量数据库、GraphRAG、检索优化和知识库更新。
- [万字详解 RAG 基础概念](./rag/07%20-%20万字详解%20RAG%20基础概念.md)：理解 RAG 是什么、为什么需要它、核心优势和局限性。
- [RAG 文档处理与切分策略](./rag/08%20-%20RAG%20文档处理与切分策略：从解析、清洗、Chunking%20到多模态内容处理.md)：覆盖文档解析、清洗、结构化、Chunking 和多模态内容处理。
- [万字详解 RAG 向量索引算法和向量数据库](./rag/27%20-%20万字详解%20RAG%20向量索引算法和向量数据库.md)：掌握 HNSW、IVFFLAT 等索引算法和向量数据库选型。
- [万字详解 RAG 检索优化](./rag/09%20-%20万字详解%20RAG%20优化：从召回、重排到上下文工程的系统调优.md)：覆盖 Chunk 策略、Hybrid Search、Query Rewrite、Rerank 和上下文压缩。
- [万字详解 GraphRAG](./rag/28%20-%20万字详解%20GraphRAG：为什么只靠向量检索撑不起复杂知识问答.md)：理解实体、关系、社区发现、全局检索与局部检索。
- [RAG 知识库文档更新策略](./rag/29%20-%20RAG%20知识库文档如何更新：增量更新、版本控制、去重与全量重建.md)：掌握增量更新、版本控制、去重和全量重建。

### AI 系统设计

- [AI 系统设计专题](./system-design/30%20-%20AI%20系统设计专题：生产级架构、模型网关、评测治理与语音%20Agent.md)：把 Prompt Demo 放进真实后端系统里看，重点关注架构、模型网关、语音链路、可观测、评测和安全治理。
- [AI 应用系统设计](./system-design/10%20-%20AI%20应用系统设计：从%20Prompt%20Demo%20到生产级架构.md)：把 Prompt Demo 放进生产链路，覆盖 Prompt 管理、模型网关、RAG、Memory、Tool 调用、可观测、评测和安全合规。
- [大模型网关详解](./system-design/11%20-%20大模型网关详解：多模型路由、fallback、限流与成本控制.md)：理解 LLM Gateway 的多模型路由、fallback、限流配额、成本归因、观测审计和缓存策略。
- [AI 语音技术详解](./system-design/31%20-%20AI%20语音技术详解：从%20ASR、TTS%20到实时语音%20Agent%20的工程化落地.md)：拆解 VAD、ASR、LLM、TTS、流式播放、打断处理和端云混合选型。

## 高频问题

- 大模型的 Token、上下文窗口、Temperature、Top P 分别会影响什么？
- 为什么结构化输出不能只依赖 Prompt？JSON Schema、Function Calling 和服务端校验分别解决什么问题？
- Agent 和 Workflow 有什么区别？Agent Loop 中观察、规划、行动、反思如何协作？
- Prompt Engineering 和 Context Engineering 有什么区别？
- MCP 解决了什么问题？它和 Function Calling、Tool Calling 是什么关系？
- RAG 为什么会答非所问？应该从召回、排序、上下文压缩还是生成阶段排查？
- 向量数据库如何选型？HNSW、IVFFLAT 这些索引适合什么场景？
- AI 应用怎么评测？Golden Set、LLM-as-Judge、线上灰度和 Trace 回放如何串起来？
- 生产级 AI 应用为什么需要模型网关？如何做限流、fallback、成本控制和审计？

## 相关专题

- [AI 编程实战指南](../ai-coding/)
- [系统设计](../system-design/)
- [高可用系统知识体系](../high-availability/)
- [高性能系统知识体系](../high-performance/)
- [分布式系统知识体系](../distributed-system/)

<!-- @include: @article-footer.snippet.md -->
