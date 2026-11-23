# 图表索引(diagram index)

> 派生物:本索引从各 `.puml` 源文件重建;条目与文件一一对应。分析对象:`haotem/AgentServerCenter`(核对于 2026-11-22 工作树)。各图的「为什么/看什么/依据/维护规则」见 [DIAGRAMS.md](DIAGRAMS.md)。

## 覆盖表

| # | 图(读者的问题) | 源文件 | 漂移锚点(描述的模块) | 渲染件 | 状态 |
|---|---|---|---|---|---|
| 01 | 报修会话会在哪些状态,什么推动它迁移(状态机) | `01-会话状态机.puml` | `app/api/routes.py::_api_state` + `app/core/engine.py::route_router` | 未渲染 | **待渲染** |
| 02 | 建会话与采集轮:谁跟谁说话,按什么顺序(时序) | `02-建会话与采集轮时序.puml` | `app/api/routes.py`(五接口) + `app/core/engine.py`(intake/extract/update/ask) | 未渲染 | **待渲染** |
| 03 | 提交之后单落谁家,失败怎么办(时序) | `03-确认与建单时序.puml` | `app/core/engine.py::submit` + `app/skills/repair/tools.py::create_repair_order` | 未渲染 | **待渲染** |
| 04 | 系统周围有什么,怎么连接(组件/部署) | `04-系统组件与部署.puml` | `app/main.py::lifespan`(装配清单) | 未渲染 | **待渲染** |
| 05 | 启动要过哪些闸,哪些问题当场炸(活动) | `05-启动与技能包挂载自检.puml` | `app/skills/repair/__init__.py::load` + `app/main.py::lifespan` | 未渲染 | **待渲染** |
| 06 | LLM 不可用时这轮对话怎么活(活动) | `06-降级与卡片向导.puml` | `app/core/degrade.py` + `engine.py` extract/fallback | 未渲染 | **待渲染** |

## 渲染命令

**仓库目前没有渲染工具链**(`scripts/` 只有代理脚本,无 Makefile/render 脚本,既有 `diagrams/*.puml` 也未配渲染步骤)。按方法论这归仓库 owner 补,不绕道:建议加一条

```bash
plantuml -tsvg src/main/tmp/diagramming-processes/*.puml   # 或 docker run --rm -v $PWD:/p plantuml/plantuml
```

工具补齐前,渲染件全部记为「待渲染」(如上表),不提交任何手工截图。

## 待渲染清单

- [ ] 01-会话状态机.puml
- [ ] 02-建会话与采集轮时序.puml
- [ ] 03-确认与建单时序.puml
- [ ] 04-系统组件与部署.puml
- [ ] 05-启动与技能包挂载自检.puml
- [ ] 06-降级与卡片向导.puml

## 维护规则(同一变更内)

改动流程的变更必须同改对应图源 + 本索引行;源文件语法错误只有渲染时才暴露,改完先本地跑一次渲染工具再提交。图回答的问题太大、没人愿意更新时,沿独立变化的接缝拆图,不硬撑。
