# 参考:配置项

定义源:`app/config.py`(`Settings`,pydantic-settings)。加载优先级恒为 **环境变量 > Nacos > .env > 代码默认值**;配置只在启动读一次,**修改后必须重启**。

Nacos 中的键名与环境变量同名(大写扁平 YAML,只允许「大写键: 标量」;模板见 `docs/nacos-agent-server-center.yaml`)。

## 会话与模式

| 键 | 默认值 | 说明 |
|---|---|---|
| `CHECKPOINT_MODE` | `memory` | `memory`=进程内(零依赖、重启丢会话、无归档);`postgres`=检查点+活跃索引+归档同库(来源:`app/config.py:15`) |
| `PG_DSN` | `postgresql://agent:agent@localhost:5432/agent` | 仅 postgres 模式使用;agent 进容器时主机改服务名 `postgres`。有意不进 Nacos |
| `ALLOW_ANONYMOUS` | `false` | 缺 `X-Uid` 时唯一退路(回 `anonymous`);仅限本地演示,**生产禁止**(来源:`app/config.py:18`) |
| `ACTIVE_TTL_DAYS` | `7` | 会话留存天数:活跃索引与检查点 TTL 同源,一处改两处生效(来源:`app/config.py:49`) |

## 身份与日志

| 键 | 默认值 | 说明 |
|---|---|---|
| `LOG_LEVEL` | `INFO` | INFO=业务主线;DEBUG 追加 LLM 完整输入输出等排查细节 |
| `BASE_SHOP_URL` | `""` | base-shop 主数据地址(门店/员工档案/大区);未配置时门店/报修人/手机号走 Mock 演示值 |

## 模型(必填,无开关)

| 键 | 默认值 | 说明 |
|---|---|---|
| `LLM_BASE_URL` | `""` | 百炼 OpenAI 兼容端点;**缺失拒启动**,不静默降级(`app/main.py` `build_llm`) |
| `LLM_API_KEY` | `""` | 同上 |
| `LLM_MODEL` | `""` | 模型名 |

## 真实建单(明道云 v3)

| 键 | 默认值 | 说明 |
|---|---|---|
| `REAL_ORDER_ENABLED` | `true` | 唯一模式开关。`true`=真实写明道云、字典走在线真值;`false`=`MOCK-` 单号、字典退静态。`true` 时下面四项必填,缺则启动即拒(来源:`app/config.py` `validate_order_config`) |
| `MDY_BASE_URL` | `""` | 明道云开放接口前缀(实测带 `/api`) |
| `MDY_APPKEY` / `MDY_SIGN` | `""` | 凭据 |
| `MDY_WORKSHEET_ID` | `""` | 报修工作表;换表只改配置 |
| `MDY_CATEGORY_WORKSHEET_ID` | `""` | 分类字典真值表「报修问题分类分级」 |
| `MDY_TRIGGER_WORKFLOW` | `false` | true=建单触发明道云派单工作流;测试单保持 false |

## 运行参数(运营可调)

| 键 | 默认值 | 说明 |
|---|---|---|
| `LLM_QUOTA_PER_SESSION` | `30` | 单会话 LLM 次数上限(成本闸):超限转卡片向导,照样能交单 |
| `FALLBACK_AFTER_FAILS` | `15` | 连续边界轮(跑题/识别失败)次数,达到即降级向导 |
| `PHOTOS_MAX` | `5` | 单会话照片/附件上限;**注意**:请求模型在 import 期固化,改后必须重启才生效(`app/api/routes.py:33-34`) |

## Nacos 引导参数(只在 .env/环境变量,不进 Nacos)

| 键 | 默认值 | 说明 |
|---|---|---|
| `NACOS_SERVER_ADDR` | (未设=不启用) | `host:8848`;客户端还需连通 9848(grpc) |
| `NACOS_DATA_ID` | `agent-server-center.yaml` | |
| `NACOS_GROUP` | `AGENT_GROUP` | |
| `NACOS_ACCESS_KEY` / `NACOS_SECRET_KEY` | | MSE 鉴权 |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | | 实例用户名密码鉴权 |

## 启动校验清单

- 缺 `LLM_BASE_URL`/`LLM_API_KEY` → `build_llm` 拒启动(在使用点校验,Mock 桩跑测试不受影响)
- `REAL_ORDER_ENABLED=true` 且 MDY 四项缺一 → `validate_runtime` 拒启动
- 技能包装配有问题(槽位重名/缺话术/缺字典)→ `load()` 拒启动
- Nacos 配置格式违规(非大写键/非标量)→ 拒启动

> 凭据类配置的真值统一放 Nacos(或部署环境的 .env/K8s 注入),**不要写进代码或提交到仓库**(仓库内 `docs/nacos-agent-server-center.yaml` 含测试环境真值,仅作模板参考)。
