# 接口参考(契约面,详尽)

描述对象:app/api/routes.py(业务五路径)与 app/main.py(探针)。仓库另有 apifox-openapi.json 手工契约(与代码同步靠人工,无自动校验)。

## 通用约定

- 身份:请求头 X-Uid 必填,由网关/Java 转发层以认证结果替换注入;缺失或全空白时五个业务接口一律 401,错误体 code=40101(本地演示可显式开 ALLOW_ANONYMOUS=true 回退匿名,生产禁止)。
- 错误体统一形状:{\"detail\": {\"code\": 五位整数, \"message\": 中文提示}};HTTP 状态码 = code 整除一百(40101→401、40401→404、40901→409、42201→422、50201→502)。
- 未处理异常兜底:任何穿透到框架层的异常统一 503、code=50301,不再回 FastAPI 默认 500。
- 服务不设 CORS;只经网关同源暴露。
- 全部业务响应为同步 JSON。

## 端点一览(Measured:装饰器 grep,见 coverage.md)

| 方法与路径 | 用途 |
|---|---|
| POST /agent/repair/v1/sessions | 建会话/恢复 |
| POST /agent/repair/v1/sessions/{sid}/messages | 发一轮消息 |
| GET 或 POST /agent/repair/v1/sessions/{sid} | 会话内刷新/超时对齐(两者同义,GET/POST 同一处理函数) |
| POST /agent/repair/v1/sessions/{sid}/submit | 提交建单 |
| POST /agent/repair/v1/sessions/{sid}/exit | 主动退出 |
| GET /healthz | 活体探针 |
| GET /readyz | 就绪探针 |

## 逐端点

### POST /sessions

请求体:{store_no: 门店编号(必填,非空、不含空白字符), restart: 是否放弃当前活跃会话另起新单(默认 false)}。

响应:{session_id, is_resumed, context(带出字段与掩码形态), messages(新建为首屏三件套;恢复为全量消息含 role=user 回显)}。

行为:同 uid 同店有未完成且未出单的活跃会话则恢复并续期;索引指向已出单/悬空会话则清理另建;restart=true 在入口锁内先放弃再新建。

### POST /sessions/{sid}/messages

请求体(六种手势之一,进入引擎前先过本地校验,规则全读技能包声明):

| type | 载荷 | 入口校验 |
|---|---|---|
| text | content | 非空非空白 |
| card | card_id | 必须是技能包已注册的快捷卡 |
| enum_pick / seg_pick | field + value | field 必须是声明该卡片形态的槽位;value 必须在当前字典白名单内 |
| phone | use_default 或 phone | 二者互斥;自定义号必须过手机识别器(11 位大陆号) |
| upload_done | attachments:[{name,url}] | 非空、url 不得重复、单次不得超过照片上限(默认 5);附件引用必须是完整 HTTP(S) 地址 |

响应:{state, focus, messages}。state 取值:collecting(采集中)、confirm(确认单展示中)、fallback(降级向导)、done(已出单)。messages 只含本轮新增(用户那条前端已自绘,不回发)。

错误:40401 会话不存在或非本人;42201 会话已提交不能再改、或合并后附件总数超上限。

行为:同会话串行(入口互斥锁);已出单的会话锁死;每轮活跃给索引续期。

### GET/POST /sessions/{sid}

响应:{state, focus, collected(已收集字段;声明显示形态的槽位只出显示形态,手机号掩码与状态条同口径), context, messages(全量重放,含 role=user 回显)}。前端断线重连或超时对齐用。

### POST /sessions/{sid}/submit

无请求体(接口向引擎注入内部手势 submit)。

响应:{order_no, status}。

行为:仅确认态可提交;幂等(同会话已出单原号返回,并补做崩溃时漏掉的归档);提交与发消息互斥。校验顺序:引擎全量重校验(必填、字典、长度、识别器、附件数)加包级校验(照片至少一张);未过的把缺项塞回议程并回 42201 附带最近一条系统提示文案。

错误:40901 不在确认态;42201 校验未过;50201 建单链路失败(确认单保留可重试)。

### POST /sessions/{sid}/exit

请求体:{mode: \"keep\" 或 \"abandon\"}(其他值 42201)。

响应:{result: \"ok\"};keep 模式额外返回 draft_expired_at(草稿保留截止时间,与检查点 TTL 同源七天)。

行为:幂等(会话已终结直接回 ok)。abandon 归档加销毁检查点;已出单的会话忽略放弃。

### GET /healthz

响应:{status: \"ok\", skills: [已加载技能包名列表]}。进程活着就 200。

### GET /readyz

响应 200 或 503:{ready, detail, mode(检查点模式), llm_model, real_order_enabled, production_ready, mock_dependencies}。postgres 模式探活 PG(能跑一条 SELECT 就算通),首次就绪时建表并启动清理线程;memory 模式恒就绪。注意 production_ready 恒为 false、mock_dependencies 恒为固定三项,与真实接入状态无关联(见 findings.md)。

## 服务端消息类型(Measured:引擎与卡片组装代码枚举)

| type | 方向 | 载荷要点 |
|---|---|---|
| text | server | {content} |
| system_tip | server | {content, level: warn 或 info} |
| status_bar | server | 首屏带出字段展示 |
| quick_cards | server | 快捷卡列表(当前字典白名单过滤后的) |
| enum_card | server | {field, mode: top3 或 grouped 或 full, options, preselect}(top3 为模型提名,永不自动入槽) |
| seg_card | server | {field, options:[{value,label}]} |
| photo_guide | server | {need_count, tips}(拍摄清单按类别现值挑) |
| phone_confirm | server | {phone_masked, use_default:true}(仅档案默认号可用时发) |
| confirm_card | server | {groups: 分组字段与显示形态值} |
| result | server | 建单结果(order_no 加提示) |
| (用户手势回显) | user | 建会话恢复与刷新重放时出现,type 同请求手势,载荷为请求体除 type 外原样 |

消息按全会话单调递增的 seq 排序,同 seq 覆盖(崩溃重放语义);前端以服务端重放为完整对话的真相。
