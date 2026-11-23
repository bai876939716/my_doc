# 参考:目录结构

仓库:`AgentServerCenter`。核心约定:**框架层(`app/core/`)业务无关、所有技能包共用;业务差异全部收在技能包(`app/skills/`)的声明式配置里**。

```
AgentServerCenter/
├── app/
│   ├── main.py              # FastAPI 装配:lifespan(自检→LLM→检查点后端→注册表)、/healthz /readyz、全局异常兜底
│   ├── config.py            # pydantic-settings 全部配置项 + 启动校验(validate_runtime)
│   ├── api/                 # 接口层
│   │   ├── routes.py        # 业务五接口(/agent/repair/v1/**):请求校验读技能包声明
│   │   └── sessions.py      # SessionRegistry:检查点 + 活跃索引 rp:active:{uid}:{store_no} + 进程内双锁
│   ├── core/                # 框架层(业务无关,技能包共用)
│   │   ├── engine.py        # LangGraph 图构建与轮次流转(打字轮/点选轮/确认/提交/降级)
│   │   ├── state.py         # 会话 State 结构(messages/slots/meta/context)
│   │   ├── skill_contract.py# SkillDef/Slot 等契约类型——技能包与框架的边界
│   │   ├── llm.py           # 百炼网关(OpenAI 兼容)封装
│   │   ├── pgstore.py       # PG:连接池、活跃索引、归档表、TTL 清理线程
│   │   ├── mingdao.py       # 明道云 v3 开放接口客户端(建单)
│   │   ├── employee_client.py # base-shop 员工档案(X-Uid→报修人/手机号)
│   │   ├── store_client.py  # base-shop 门店主数据
│   │   ├── nacos.py         # Nacos 配置读取(import 期)+ 启动校验
│   │   ├── attachments.py   # 附件引用结构 {name,url}
│   │   ├── degrade.py       # 降级向导:边界识别器(DETECTORS)
│   │   └── redact.py        # 敏感值出口(手机号掩码,唯一实现)
│   └── skills/
│       └── repair/          # 本期技能包:设备报修(声明式,无流程代码)
│           ├── skill.py     #   SkillDef 包声明(快捷卡片/工具名/摘要槽位)
│           ├── slots.py     #   槽位 schema(字段/字典/手势/卡片形态)
│           ├── fields.py    #   字段
│           ├── dict_sources.py  #   字典来源(静态↔在线真值)
│           ├── category_source.py / region_source.py  # 类别/大区字典
│           ├── talks.py     #   话术
│           ├── prompts.py   #   LLM 提示词
│           ├── tools.py     #   工具(含建单)
│           ├── cards.py     #   卡片
│           └── mock_llm.py  #   技能包级 LLM 桩
├── tests/                   # pytest(testpaths 配于 pyproject.toml)
│   ├── conftest.py          #   monkeypatch build_llm——普通用例全换桩、不联网
│   ├── test_api.py          #   五接口契约
│   ├── test_engine.py       #   引擎流转
│   ├── test_dict_sources.py #   字典来源
│   ├── test_infra.py        #   基础设施
│   └── test_neutrality.py   #   框架中立性(假技能包全链路)——core 不许耦合业务
├── poc/                     # 演示与探针脚本(不进生产)
│   ├── local_demo_app.py    #   演示入口:生产 app + 一层 CORS
│   ├── mock_llm_app.py      #   零 token 演示入口(LLM 换桩)
│   ├── 门店报修-AI填报-*.html #  正式版/演示版页面
│   ├── e2e_real_order.py    #   真实建单 e2e
│   └── llm_smoke.py / dump_worksheet_fields.py / field_write_probe.py
├── scripts/
│   ├── dev_gateway_proxy.py #   本机联调代理(补网关鉴权,见 docs/本地联调.md)
│   └── test_env_proxy.py
├── eval/                    # 评测(cases/ 用例、reports/ 报告)
├── docs/                    # nacos 模板、数据留存与埋点查询、本地联调
├── diagrams/                # 图
├── docker-compose.yml       # agent(可选)+ postgres:17(检查点库,命名卷持久化)
├── Dockerfile
├── pyproject.toml / uv.lock # 依赖:改 pyproject → uv lock → 两个文件一起提交
└── apifox-openapi.json      # 接口文档(Apifox;X-Uid 在此标必填)
```

## 分层铁律

1. `app/core/` 不 import 任何 `app/skills/*`——中立性由 `tests/test_neutrality.py` 守护。
2. 接口层校验口径**只读技能包声明**(槽位/字典/卡片白名单),不自己维护字段副本(来源:`app/api/routes.py` 模块 docstring)。
3. 新业务 = 新技能包 + 同形状路由,core 零改动。
