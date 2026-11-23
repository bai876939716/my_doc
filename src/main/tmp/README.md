# AgentServerCenter · 7 个文档 Skill 对比运行结果

- 分析对象:`/Users/harvey/Documents/projects/haotemai/AgentServerCenter`(纯 Python 3.12,FastAPI + LangGraph,51 个源码文件 / 7966 行,HEAD `fc8e8f3`)
- 运行日期:2026-11-22;每个 skill 一个目录,独立运行、互不参考

## 目录导航

| Skill | 产出 | 入口 | 特点 |
|---|---|---|---|
| documenting-legacy-codebases | 18 个文件 | `documenting-legacy-codebases/README.md` | 普查式重建文档树:inventory + coverage 台账 + findings(12 条疑似缺陷)+ unknowns(7 条) |
| technical-writing | 1 个文件 | `technical-writing/SYSTEM_OVERVIEW.md` | 单篇系统说明,证据纪律最严:每句论断带 `文件:行号`,README 与代码冲突处如实标注 |
| diagramming-processes | 8 个文件 | `diagramming-processes/INDEX.md` | 6 张 PlantUML 图源(沿用仓库既有 diagrams/ 的 PlantUML 惯例):状态机、时序、部署、自检、降级 |
| documentation-writer (Diátaxis) | 13 个文件 | `documentation-writer/README.md` | 四象限完整文档集:tutorial ×1、how-to ×5、reference ×3、explanation ×3 |
| improve-codebase-architecture | 1 个文件 | `improve-codebase-architecture/report.html` | 可视化 HTML 报告,7 个 deepening 候选(Strong/Worth exploring/Speculative 分级) |
| java-docs ⚠️适配 | 2 个文件 | `java-docs/ADAPTATION.md` | 项目无 Java;按 Javadoc 严谨度平移为 Python API 参考 |
| java-springboot ⚠️适配 | 2 个文件 | `java-springboot/ADAPTATION.md` | 项目无 Spring;五维检查单映射到 FastAPI 等效审查 |

⚠️ = 适配运行(见各自 ADAPTATION.md 的适用边界说明)。

## 交叉发现(值得优先看)

- **`.env` 明文凭据被 git 追踪** — java-springboot 审查发现的最高风险项,建议轮换密钥并移出版本库
- **12 条疑似代码缺陷** — `documenting-legacy-codebases/findings.md`(均带 file:line)
- **7 个架构改进候选** — `improve-codebase-architecture/report.html`,Top 2:路由工厂化、core 直读 settings

## 比较建议

同一项目、同一天、不同方法论,横向看四个维度:①结构是否可维护(文档树 vs 单篇);②事实纪律(引用密度、冲突处理);③上手可用性(新手照哪份能跑通);④信噪比(废话率)。
