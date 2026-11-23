# AgentServerCenter 文档树(按代码重建)

本树由 documenting-legacy-codebases 方法论于 2026-11-22 从代码 HEAD(dev 分支,提交 fc8e8f3)重建,目标是:下一位工程师敢信、下一次重构不用改(只有行为变更或新功能才需要回来编辑)。代码是唯一权威;仓库原有 README 与 docs/ 下的三份文档按「证词」对待,与代码冲突处已在文档中点名。

## 怎么读

每篇文档开头第一句标注它描述的模块路径(漂移检查的锚点)。正文不出现代码引用;确切的 file:line 只出现在 findings.md(给 owner 的工作材料)。

置信层级(全树统一):

- **Measured**:由文档中给出的命令在代码 HEAD 上复现得出(计数、枚举)。
- **Sourced**:逐行读代码得出,引用处标注来源文件。
- **Estimated**:依据代码行为做的推断,均已标注推断依据。

## 索引

| 文档 | 一句话用途 |
|---|---|
| overview.md | 系统边界、分层与真实依赖图(从 import 与装配得出) |
| engine.md | 采集图引擎:节点、路由、六道闸门、降级机制 |
| skill-pack.md | 技能包契约与报修技能包的实际配置 |
| sessions.md | 会话生命周期:注册表、互斥锁、留存与归档 |
| llm-gateway.md | 模型网关:提示词协议、输出归一、重试与兜底 |
| api-reference.md | 全部端点、消息类型、错误码(契约面,详尽) |
| config-reference.md | 全部 28 个配置键:默认值、绑定证据、用途 |
| integrations.md | 五个外部系统:方向、协议、失败行为 |
| data-model.md | 会话黑板结构与 PG 三张应用表的生命周期 |
| processes.md | 端到端业务过程(diagrams/ 内附图源) |
| history.md | git 历史挖出的行为变迁(109 个提交,推断已标注) |
| findings.md | 疑似缺陷与文档漂移清单,一行一条带 file:line |
| unknowns.md | 本次未能确定的事项,以及放弃前查过什么 |
| coverage.md | 覆盖台账:各表面分母、计数命令、逐文档状态 |

## 与仓库现有文档的关系

仓库自带 README.md(运维口径)与 docs/ 下三份(Nacos 配置样例、数据留存查询、本地联调指南)、diagrams/ 下两份 PlantUML 图源。本树不取代它们:README 与 docs/ 的运维内容仍然有效,本树补齐的是「系统如何工作」的描述层;两处对同一事实表述不一致时,以本树(已对代码核实)为准,并在 findings.md 记录分歧。
