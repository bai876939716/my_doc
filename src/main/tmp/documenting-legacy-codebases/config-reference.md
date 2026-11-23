# 配置参考(契约面,详尽)

描述对象:app/config.py(Settings,19 键)与 app/core/nacos.py(NacosBootstrap,9 键,前缀 NACOS_)。

优先级:环境变量 > Nacos(注入到环境变量且不覆盖已有值)> .env 文件 > 代码默认值。全部配置启动读一次,改了要重启(代码注释明说;其中照片上限在路由模块 import 期就被定死进请求模型,属于最硬的一档)。未发现死键:下列每键都有读取点(逐键核对绑定见 coverage.md 命令)。

## 应用配置(环境变量名 = 字段名大写)

| 键 | 默认 | 绑定证据(读取点) | 用途 |
|---|---|---|---|
| CHECKPOINT_MODE | memory | 装配层选 saver | 检查点后端:memory 进程内(重启丢会话)/ postgres 落库 |
| ALLOW_ANONYMOUS | false | 身份闸门 | 缺 X-Uid 时是否回退匿名(本地演示用;生产禁开,开了等于把所有匿名用户并成同一份草稿) |
| LOG_LEVEL | INFO | 日志装配 | INFO 业务主线;DEBUG 追加 LLM 完整输入输出等排查细节 |
| REAL_ORDER_ENABLED | true | 建单工具与字典源 | 唯一模式开关:true 建单真实写明道云、字典走在线真值;false 返回 MOCK- 单号、字典退静态 |
| PG_DSN | postgresql://agent:agent@localhost:5432/agent | PG 建池 | 检查点、活跃索引、归档表同库(postgres 模式) |
| BASE_SHOP_URL | 空(未配置) | 技能包工具 | base-shop 主数据地址;未配置全走 Mock 演示值;查无/异常退空不伪造 |
| LLM_BASE_URL | 空 | LLM 构造点 | 百炼 OpenAI 兼容端点;缺失启动即拒 |
| LLM_API_KEY | 空 | LLM 构造点 | 同上 |
| LLM_MODEL | 空(无默认) | LLM 构造点 | 模型名(日志与就绪探针回显) |
| MDY_BASE_URL | 空 | 明道云客户端惰性构造 | 明道云 v3 开放接口前缀(实测带 /api) |
| MDY_APPKEY | 空 | 同上 | 固定头 HAP-Appkey |
| MDY_SIGN | 空 | 同上 | 固定头 HAP-Sign(授权页生成后原样带,不是动态签名) |
| MDY_WORKSHEET_ID | 空 | 建单工具 | 报修工作表 id(换表只改配置) |
| MDY_CATEGORY_WORKSHEET_ID | 空 | 分类字典源 | 分类字典真值表 id |
| MDY_TRIGGER_WORKFLOW | false | 建单工具 | 建单是否触发明道云派单工作流(测试单保持关) |
| LLM_QUOTA_PER_SESSION | 30 | 降级判定 | 单会话 LLM 次数成本闸,超限转卡片向导照样交单 |
| FALLBACK_AFTER_FAILS | 15 | 路由表 | 连续边界轮(跑题/识别失败)多少次触发降级向导 |
| PHOTOS_MAX | 5 | 路由请求模型(import 定死)、上传处理、提交校验 | 单会话照片/附件上限 |
| ACTIVE_TTL_DAYS | 7 | 活跃索引 TTL 与清理线程 | 会话留存天数(索引与检查点 TTL 同源) |

联动约束:REAL_ORDER_ENABLED=true 时 MDY_BASE_URL、MDY_APPKEY、MDY_SIGN、MDY_WORKSHEET_ID 四项必须齐全,缺则启动拒绝(validate_order_config);LLM 配置不在启动校验链上,由构造点在使用时校验(测试换桩不需要密钥)。

## Nacos 引导配置(前缀 NACOS_,仅 NACOS_SERVER_ADDR 非空时启用)

| 键 | 默认 | 用途 |
|---|---|---|
| NACOS_SERVER_ADDR | 空 | 配置中心地址;空 = 不启用 Nacos。设了就连不通拒启动(fail-fast) |
| NACOS_NAMESPACE | 空 | 命名空间 |
| NACOS_GROUP | AGENT_GROUP | 配置分组 |
| NACOS_DATA_ID | agent-server-center.yaml | 配置文档名 |
| NACOS_ACCESS_KEY / NACOS_SECRET_KEY | 空 | MSE 鉴权对(必须同时配置) |
| NACOS_USERNAME / NACOS_PASSWORD | 空 | 实例用户名密码鉴权对(必须同时配置) |
| NACOS_TIMEOUT_MS | 5000 | 配置读取超时 |

Nacos 内容约束(读取层强校验):扁平 YAML,键必须是大写环境变量名形状,值只支持标量;违反任一条拒启动。仓库 docs/ 下有配置样例文档。import 期同步读取(独立线程跑事件循环),早于日志初始化,就绪后补报一行。

## 仓库内配置样例分歧

README 提到「DSN 见 .env.example」,但仓库内没有 .env.example 文件(有未入库的 .env);该指引指向不存在的文件,见 findings.md。
