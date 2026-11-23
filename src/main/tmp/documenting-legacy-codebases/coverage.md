# 覆盖台账

普查与建档于 2026-11-22 执行,对象为 dev HEAD(提交 fc8e8f3)。计数会漂:命令重跑才有意义,文档不随漂移更新(行为变更才更新)。

工作区与 HEAD 的差异:app/main.py 有一行未提交修改(日志 datefmt 增加 %Y-%m-%d 前缀),为建档前已存在的用户改动,不影响本树任何已记录的行为声明;除该行外,读取的代码与 HEAD 一致。

## 表面清单与分母(Measured,命令在代码 HEAD 跑过)

| 表面 | 分母 | 命令 | 已建档 |
|---|---|---|---|
| 源码文件 | 51 个 py(排除 .venv 与缓存) | find . -name \"*.py\" -not -path \"./.venv/*\" -not -path \"*__pycache__*\" \| wc -l | overview/engine/skill-pack/sessions/llm-gateway 五篇描述文档覆盖 app/ 全部模块 |
| 源码行数 | 7966 行 | 同上,-exec cat {} + \| wc -l | 同上 |
| 业务端点 | 5 路径(6 处理函数,其一 GET/POST 同义)+ 2 探针 | grep 路由装饰器 | api-reference.md 全量,7/7 |
| 配置键 | 19 应用键 + 9 Nacos 键 = 28 | grep 字段声明(config.py; nacos.py) | config-reference.md 全量,28/28;死键 0 |
| 外部集成 | 5 个系统 | 逐模块读出 | integrations.md,5/5 |
| PG 应用表 | 3 张(+框架表,见 unknowns #1) | DDL 常量枚举 | data-model.md,3/3 |
| 服务端消息类型 | 10 种 + 用户回显 | 引擎与卡片代码枚举 | api-reference.md,11/11 |
| 槽位 | 7 | slots.py 枚举 | skill-pack.md,7/7 |
| 测试函数 | 124 | grep -c 计数 | 未逐条核对(unknowns #4) |
| 端到端过程 | 4(首次打字/快捷卡/确认态改值/降级) | 代码路径追踪 | processes.md,4/4 + 图 3 张 |
| git 提交 | 109(2026-11-07 至 11-21) | git rev-list --count HEAD | history.md 按主题归并 |
| 历史图源 | 仓库自带 2 份 PlantUML | ls diagrams/ | 本树另建 3 份 mermaid,未改动原件 |

## 逐文档状态

单写者顺序执行(无并行,单写者规则自然满足)。全部为 drafted(已起草、已对代码核实);reviewed 需要第二名读者,本次没有。在文档获得 review 前,其中的声明不应被当作已审定。

| 文档 | 状态 | 核实方式 |
|---|---|---|
| README.md | drafted | 索引派生,数量对台账核对 |
| overview.md | drafted | 逐模块读代码(import 与装配) |
| engine.md | drafted | engine.py 逐行(全文 1022 行) |
| skill-pack.md | drafted | 技能包七文件全读 |
| sessions.md | drafted | sessions.py/pgstore.py/main.py 全读 |
| llm-gateway.md | drafted | llm.py 全读 + prompts 概要 |
| api-reference.md | drafted | routes.py/main.py 逐行 + 消息类型 grep 枚举 |
| config-reference.md | drafted | config.py/nacos.py 逐字段;绑定读取点逐键 grep |
| integrations.md | drafted | 五个客户端模块全读 |
| data-model.md | drafted | state.py/pgstore.py DDL/fields.py 全读 |
| processes.md | drafted | 引擎路径追踪(跨层一条真实场景走通) |
| diagrams/ 3 份 | drafted | 同上,状态迁移逐一在代码找到依据 |
| history.md | drafted | git log 全量主题归并,推断逐条标注 |
| findings.md | drafted | 12 条,每条带 file:line |
| unknowns.md | drafted | 7 条,每条写明查过什么 |

## 中断续接

若活动中断,从本表看:全部文档已起草完毕,剩余工作是 review(每篇需第二读者)与 unknowns.md 七条的确定方法执行。没有半成品文件。

## 与代码分歧的记录方式

README/config-reference/findings 三处点名了两处分歧(README 的 .env.example 指引悬空;apifox 手工契约无自动校验);代码与仓库 README 冲突时文档以代码为准。
