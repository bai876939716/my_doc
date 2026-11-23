# AgentServerCenter 流程图集(diagrams-as-source)

> 按 `diagramming-processes` 方法论产出:图是「画出来的主张」,每个框和箭头受与正文同等的真相规则约束;源文件为版本控制中的文本,渲染件一律视为派生物。分析对象:`/Users/harvey/Documents/projects/haotemai/AgentServerCenter`(2026-11-22 工作树,以下所有 `file:line` 均指该仓库)。

**工具链说明(方法论决定):** 仓库既有图目录 `diagrams/` 使用 PlantUML(`01-通用采集图-代码版.puml` 等),故本图集沿用 PlantUML,不引入第二套工具。未使用 mermaid 的原因即此——一仓一工具链,渲染件可重建。

**每图一问**:一张图回答一个读者问题;两个问题的图拆开。图不重述正文步骤,正文(本文件各节)负责「为什么存在、看什么」。

---

## 01 会话状态机 —— 报修会话会在哪些状态,什么推动它迁移?

**为什么存在:** 等待用户确认在这个系统里是数据(`meta.confirm_pending`)不是控制流(engine.py 模块注释),LangGraph 图没有 interrupt 挂起态可查,所以「会话处于什么状态」完全靠黑板上的标记位推导——这正是 prose 序列化 badly、图承载更好的分支与状态。

**看什么(quirks,图上是内容不是装饰):**

- **done 是锁死态**:出单后再发消息 42201(routes.py:224-229),重复提交原号返回(routes.py:275-279 + engine.py:894-896 双层幂等);出单后 exit 被忽略(sessions.py:146-148)。
- **确认态被打断的边界轮同样累计失败次数**——连续跑题到阈值,向导照样接管(engine.py:777-786 的路由不看 confirm_pending)。
- **降级是粘性的**:mode=fallback 后每轮打字直接归降级管(degrade.py:28-29),点选轮不受影响。
- **提交校验不过 = 回采集态**:缺项塞回议程(engine.py:921-931),补齐后自动重新走到确认。
- 状态口径取自接口层 `_api_state`(routes.py:163-171):done > confirm > fallback > collecting 的判定序。

**依据:** app/api/routes.py:163-171(状态推导)、app/core/engine.py:755-791(route_router 路由表)、engine.py:1012-1021(submit/fallback 的条件边)、app/api/sessions.py:75-158(复用/放弃)、app/core/pgstore.py(清理线程,TTL 兜底)、app/config.py:46-49(阈值默认值:15 次边界轮、30 次配额、7 天留存)。

**维护规则:** 改 route_router 的任何一个分支、_api_state 的判定序、或 config 三个阈值默认值,同一变更里更新本图。

---

## 02 建会话与采集轮时序 —— 谁跟谁说话,按什么顺序,说什么?

**为什么存在:** 一次「打字轮」横跨七个参与者(前端、网关、API、锁、图、LLM、PG),消息里还有「用户手势恒为本轮第 1 条」这类顺序契约(engine.py:334-335)——时序是唯一不扭曲的画法。

**看什么:**

- **身份信任边界在网关**:X-Uid 必须由转发层以认证结果替换注入,缺头 401/40101(routes.py:105-118);属主比对不一致回与「会话不存在」同响,防试探(routes.py:155-160)。
- **同会话串行的两把锁**:入口锁(建会话/放弃互斥,sessions.py:55-61)与会话锁(读-改-写全程持锁,routes.py:222-231)。
- **打字轮是全流程唯一 LLM 调用点**(engine.py:379-415);secret 槽位真值不进 Prompt(engine.py:65-68),手机号对外只出掩码。
- **点选轮 0 LLM**:路由层先做字典白名单校验(routes.py:53-59),引擎手势直写;OnChange 只在「改值」时跑,连带作废/重算/提示三档(engine.py:186-218)。
- 附件:前端先直传明道云、会话只收 `{name,url}` 引用(README「附件协议更新」+ engine.py:449-469)。此条为 README 与代码共同佐证。

**依据:** app/api/routes.py(五接口)、app/api/sessions.py:75-112(建会话/恢复)、app/core/engine.py:317-345(入口路由+intake)、engine.py:399-415(extract)、engine.py:601-612(update 分诊)。

**维护规则:** 新增接口、改手势协议(IncomingMsg 的 type 集)、或改锁的范围,同一变更里更新本图。

---

## 03 确认与建单时序 —— 提交之后钱(单)落谁家,失败怎么办?

**为什么存在:** 建单的幂等是三层的(接口预检 → 节点守卫 → 工具层进程内缓存),外加一个「建单失败不算出单」的不变量——失败分支与成功分支行为不同,只画 happy path 就是夸大主张。

**看什么:**

- **接口预检拦不住连点竞态**:预检在锁外读快照,真正的守卫在建单节点内(engine.py:891-896 注释)。
- **两级校验**:引擎通用校验(必填/字典/长度/识别器/附件,engine.py:902-931)→ 包级 SubmitCheck(本技能包:照片≥1,slots.py:62-64)。
- **REAL_ORDER_ENABLED 开关**:false 返回 MOCK- 单号不写明道云;true 真实建行,自动编号单号回查补齐、超时吞掉有 rowid 兜底(tools.py:106-199)。
- **建单失败保留确认态可重试**(engine.py:950-957 + 条件边 submit→confirm);归档失败有接口层补归档自愈(routes.py:276-279)。
- 提交成功即清活跃索引:重进=新单(routes.py:300)。

**依据:** app/api/routes.py:269-302、app/core/engine.py:890-964、app/skills/repair/tools.py:106-199、app/skills/repair/slots.py:62-64。

**维护规则:** 改幂等层级、校验链、或建单开关语义,同一变更里更新本图。

---

## 04 系统组件与部署 —— 这个系统周围有什么,怎么连接?

**为什么存在:** 单副本约束、可选依赖的退路(base-shop 未配置退 Mock)、配置优先级(env > Nacos > .env > 默认)——这些「系统层」关系在代码里散在五处,图收拢为一张。

**看什么:**

- **单副本约束是硬的**:会话锁与建单幂等缓存都在进程内(sessions.py:46-52 TODO;README「部署与安全约束」)。
- **引擎/技能包分层是产品主张**:core 零改动接新技能包,由 test_neutrality.py 守着(README + routes.py 模块注释)——图中 ENGINE 与 PACK 分开画就是为了承载这条线。
- 分类字典走明道云在线真值(LiveOptions 懒迭代,skills/repair/__init__.py:72)。
- LLM 缺配置拒启动(config.py:59-63 + main.py:85-93):没有「无 LLM 跑」的生产形态。

**依据:** app/main.py:96-150(lifespan 装配)、app/api/sessions.py、app/core/pgstore.py:26-77(池/表)、app/skills/repair/tools.py:23-64(外部依赖退路)、README.md(网关约定/单副本/容量口径)。容量口径(线程池40、点选轮 0 LLM)为 README 陈述,未在代码逐项复测。

**维护规则:** 接入新外部系统、改检查点后端、或解除单副本约束,同一变更里更新本图。

---

## 05 启动与技能包挂载自检 —— 服务起来时要过哪些闸,哪些问题当场炸?

**为什么存在:** 「启动即自检」是这个服务的安全主张(README:配置有问题直接拒绝启动),自检断言链有十余条、外加两条「拒启动」分支和一条「降级态运行」分支——分支密度高,是活动图的领地。

**看什么:**

- **两条拒启动分支**:真实建单配置不齐(config.validate_order_config)、LLM 配置缺失(main.build_llm 在使用点校验,不在 validate_runtime——config.py:59-63 注释)。
- **快捷卡白名单是剔除不是断言**:在线字典被运维改是常态,剔卡+告警+重启恢复,不炸(skills/repair/__init__.py:102-120)。
- **「首次就绪」兜底**:建表+首轮清理+清理线程全挂在 /readyz 首次转绿上,启动时 PG 未连上不进死区(main.py:126-133,注释标为 P1 修复)。
- 探针分离:/healthz liveness、/readyz readiness(main.py:167-192)。

**依据:** app/main.py:85-150、app/config.py:59-63、app/skills/repair/__init__.py:63-140。

**维护规则:** 加自检断言、改检查点装配、或改探针行为,同一变更里更新本图。

---

## 06 降级与卡片向导 —— LLM 不可用时这轮对话怎么活?

**为什么存在:** 降级链有三条出口(规则脑命中→零感知续跑;点名无值→挂起追问;全不中→边界轮计数),再加粘性向导接管——「LLM 挂了用户体验是什么」是运维最关心的问题,分支即答案。

**看什么:**

- **粘性**:进入向导模式后,reason() 第一条就命中(degrade.py:28-29),打字轮永远不再调 LLM。
- **规则脑与 LLM 同构**:命中就返回标准意图对象,下游(update 节点)零感知(degrade.py:80-85)。
- **识别器槽的准入很严**:光正则命中不够,还得原话点名或正在等这个值——「凭空出现的 11 位数字(订单号)不抢」(degrade.py:46-51)。
- **向导态的 quirk**:描述槽免问(卡片路径 ask_on_card_path=False + DefaultFill 按类别拼默认描述,slots.py:18-26、engine.py:966-988)——不这么画会「描述没有对应手势可填,卡死」(engine.py:970 注释原话)。
- 点选轮永远不进这条链(engine.py:752 注释:降级只换通道不改结果)。

**依据:** app/core/degrade.py:26-95、app/core/engine.py:379-415(extract 入口)、engine.py:777-781(fallback 触发)、engine.py:966-988(fallback 节点)、app/skills/repair/slots.py(detail 槽声明)。

**维护规则:** 改配额/阈值语义、规则脑命中条件、或向导免问逻辑,同一变更里更新本图。
