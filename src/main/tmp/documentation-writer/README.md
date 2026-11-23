# AgentServerCenter 文档集(Diátaxis 结构)

本目录按 [Diátaxis 框架](https://diataxis.fr/)组织,四类文档各司其职、写作模式互不混杂:

| 象限 | 面向 | 回答的问题 | 位置 |
|---|---|---|---|
| **教程 Tutorial**(学习导向) | 新手 | 「带我走一遍,保证能跑通」 | [tutorial/](tutorial/tutorial.md) |
| **操作指南 How-to**(问题导向) | 有基础的用户 | 「怎么解决这个具体问题」 | [how-to/](how-to/) |
| **参考 Reference**(信息导向) | 查阅者 | 「这个东西准确的技术描述是什么」 | [reference/](reference/) |
| **解释 Explanation**(理解导向) | 想懂原理的人 | 「为什么设计成这样」 | [explanation/](explanation/) |

## 快速导航

**教程**

- [从零跑通一次报修会话](tutorial/tutorial.md) —— 环境安装 → 起服务 → 走完一轮对话 → 提交出单

**操作指南**

- [切换到 Postgres 检查点](how-to/switch-postgres-checkpoint.md) —— 会话重启不丢
- [接入 Nacos 配置中心](how-to/configure-nacos.md) —— 配置真值集中管理
- [运行本地演示页](how-to/run-local-demo.md) —— 浏览器可视化联调
- [新增一个业务技能包](how-to/add-skill-pack.md) —— 框架层零改动扩业务
- [运行测试](how-to/run-tests.md) —— pytest 全套与真实 LLM 标记

**参考**

- [API 端点参考](reference/api-endpoints.md) —— 五接口 + 探针 + 错误码契约
- [配置项参考](reference/configuration.md) —— 全部环境变量与优先级
- [目录结构参考](reference/project-structure.md) —— 仓库布局约定

**解释**

- [架构:一个部署单元,多个技能包](explanation/architecture.md) —— core/skills/api 三层分离
- [X-Uid 信任边界](explanation/identity-trust.md) —— 为什么服务自身零鉴权
- [LLM 无开关与单副本约束](explanation/llm-and-single-replica.md) —— 两条刻意为之的硬约束

---

*事实来源:仓库代码(`app/`、`tests/`、`docker-compose.yml`、`pyproject.toml`),引用处标注文件路径;与 README 冲突时以代码为准。*
