# 发现清单(给 owner 的工作材料)

本页是疑似缺陷、文档漂移与值得复查的行为,一行一条,带 file:line。本活动只记录不修复;是否有意为之由 owner 判定。行号对应 2026-11-22 的 dev HEAD(fc8e8f3)。

1. app/main.py:192 就绪探针恒回 production_ready:false 与 mock_dependencies 三项固定列表,与真实接入状态(base-shop 已接、配置已迁 Nacos)无关联,疑似早期演示标记未跟着收尾。
2. app/skills/repair/tools.py:208 建单幂等缓存 _ORDER_CACHE 只增不减,进程生命周期内缓慢增长;单副本低量级可接受,长期运行值得复查。
3. app/skills/repair/tools.py:184 明道云建单网络超时时结果未知(可能已建单),当前行为是异常上抛 500 而非提示重试,用户手动重试存在双单窗口(代码内 TODO 自认,方案二选一:占位或对账)。
4. app/api/sessions.py:39 会话锁与入口锁共用一张常驻字典,锁对象不回收(注释自认 POC 量级无所谓);多副本场景该锁整体失效,README 与 sessions.py:50 的 TODO 双处点名。
5. app/core/engine.py:683 一轮只处理首个修改目标,复合修改(一轮说改两处)第二目标被丢弃待下轮(代码内 TODO 自认,等多目标状态机)。
6. app/api/routes.py:34 附件单次上限 max_length 在 import 期定死进请求模型,改 PHOTOS_MAX 不重启不生效(注释自认);同类 import 期绑定还有 app/api/sessions.py:20 的 TTL 常量。
7. app/api/routes.py:26 接口字段形状注释明言「改这里要同步接口文档」,而仓库 apifox-openapi.json 为手工契约,无对代码的自动校验,漂移只能靠人眼。
8. README.md 第 6 行「DSN 见 .env.example」:仓库内不存在 .env.example 文件(实测 ls -a 核对,存在的是未入库的 .env),指引悬空。
9. app/api/sessions.py:120 delete_thread 传裸 thread_id 依赖当前 saver 版本签名,依赖 langgraph 版本钉在 lock 文件;saver 升级时此处可能静默失效(catch 分支只警告)。
10. 附件上限双检:锁外预检(app/api/routes.py:213)与锁内软拒收(app/core/engine.py:461)口径一致但分两处维护,改上限时要同时想到两处。
11. 演示门店兜底:入口不带门店编号时静默落到固定演示店(app/skills/repair/tools.py:75),生产网关正常必带 store_no 时不可达;若网关漏传,用户会看到一家陌生门店的会话而不是报错(有意设计,推断依据:注释「演示门店」)。
12. app/core/nacos.py:53 用 setdefault 注入:已存在的同名环境变量(哪怕值为空串)会挡住 Nacos 值,与文档「环境变量 > Nacos」一致,但显式设了空串这种边角会静默生效为空;部署侧需避免空串占位。
