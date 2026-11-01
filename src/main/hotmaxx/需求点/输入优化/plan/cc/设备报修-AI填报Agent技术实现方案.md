# 设备报修 · AI 填报 Agent 技术实现方案

> 版本 v1 · 2026-10-31 · 供 11.4 技术评审。产品依据：[报修产品方案.md](../../prd/final/报修产品方案.md)、[产品交互流程图.puml](../../prd/final/产品交互流程图.puml)、[交互原型](./原型-AI填报对话.html)

**选型一览**

| 层 | 选型 | 说明 |
|---|---|---|
| Agent 服务 | Python 3.11 + FastAPI + SSE | 独立部署，无状态横向扩 |
| 编排 | 自研状态机（主）+ LangChain（LLM 调用封装） | 状态转移由规则驱动，LLM 只做理解/抽取，不做流程决策 |
| 模型 | 智谱 GLM-5.3（识别/抽取）+ GLM-Flash（分类/改写） | TODO：确认账号与配额 |
| 工具层 | MCP Server（fastmcp，独立进程） | 工具与 Agent 解耦，报销等业务可复用 |
| 存储 | Redis（会话/草稿，TTL 7 天）+ MySQL（会话归档/埋点） | |
| 前端 | App 内 H5 聊天页，消息协议与原型对齐 | |

---

## 1. 系统架构

> 📊 系统架构图（PlantUML 独立文件 · 样式对齐 xinyu 图库）：[diagrams/01-系统架构图.puml](../../diagrams/01-系统架构图.puml)

关键决策：LLM 不持有流程控制权——状态机决定"下一步问什么"，LLM 只负责"理解输入、产出结构化字段"。枚举、跳转、校验全部走规则，保证可测试、可回放。

---

## 2. MCP 工具清单

| 工具名 | 用途 | 入参 | 出参 | 后端依赖 |
|---|---|---|---|---|
| `get_login_profile` | 登录人档案 + 绑定门店 | token | 姓名、手机号、门店编号、角色（店员/督导） | 员工档案 TODO 接口名 |
| `get_store_master` | 门店主数据 | 门店编号 | 门店名、大区、属性、地址、营业时间 | 门店主数据 TODO 接口名 |
| `get_category_dict` | 类别枚举 + 高频 Top6 | 门店编号（可选，出个性化高频） | 类别树、Top6 卡片文案、各类别拍摄清单 | 类别字典配置表 |
| `upload_media` | 照片/视频上传 | 文件流、会话 ID | media_id、url | 对象存储 |
| `validate_photos` | 按类别校验拍摄清单 | 类别、media_id 列表 | 缺项名称列表（空=通过） | 类别字典（规则校验，非 LLM） |
| `create_repair_order` | 提交报修单 | 13 字段完整 JSON | 报修单编号、状态 | 明道云 API TODO 字段映射 |
| `draft_save` / `draft_get` | 会话草稿存取 | 会话 ID、collected_fields | OK / 草稿内容 | Redis |

> 督导多店：`get_login_profile` 返回多门店时，前端首屏弹门店切换胶囊，切换后重调 `get_store_master` 联动刷新。

---

## 3. 对话状态机

| 状态 | 进入条件 | LLM 职责 | 规则职责 | 出口转移 |
|---|---|---|---|---|
| `INIT` | 建会话 | 无 | 调工具带出 9 字段，下发定位状态条 + Top6 卡片 | 用户发文字→`A_WAIT`；点卡片→`B_LOCK` |
| `A_WAIT` | 分支A 用户输入描述 | 类别识别 prompt：Top3 候选 + 置信度；三要素抽取 | 置信度分流（见 §4 阈值） | ≥0.4→`A_ENUM`；<0.4→`FALLBACK` |
| `A_ENUM` | 有 Top3 候选 | 无 | 渲染枚举单选卡（AI 识别项标记预选） | 点选→`DETAIL`；超时 60s→提示后回卡片 |
| `B_LOCK` | 点了高频卡片 | 无 | 卡片→枚举映射锁定类别 | 立即→`DETAIL` |
| `DETAIL` | 类别已定 | 三要素抽取；缺失项生成一句追问（仅限要素本身）；润色成结构化详情 | 校验 表现/原因/后果 三要素齐 | 齐→`PHOTO`；缺→追问 1 轮后仍缺→带占位符进确认单 |
| `PHOTO` | 详情齐 | 无 | `validate_photos` 按类别清单校验 | 通过→`CONTACT`；缺项→指出缺哪张，停留 |
| `CONTACT` | 照片通过 | 无 | 展示带出联系人 + 修改入口 | 用本人→`CONFIRM`；修改→行内表单→`CONFIRM` |
| `CONFIRM` | 字段齐 | 无 | 渲染预填确认单（带出灰显/AI 高亮/✏️） | 确认→`DONE`；点重选类别→`PHOTO` 前置重走 |
| `DONE` | 提交成功 | 无 | 出单号 + 状态，推埋点 | 终态（可重新演示/继续报修） |
| `FALLBACK` | 置信度低 / 主数据缺失 | 无 | 全量类别树逐级点选；仍无法定位→引导切老表单 | 选定→`DETAIL`；切走→终态 |

> 📊 状态转移图（PlantUML 独立文件）：[diagrams/02-对话状态机图.puml](../../diagrams/02-对话状态机图.puml)

**两分支差异只在入口**：B_LOCK 无类别确认（卡片即枚举），A_ENUM 必须用户点选确认（AI 识别不入库）。汇合后流程完全一致——与交互图一一对应。

---

## 4. Prompt 工程

### 4.1 系统提示词（状态机注入到每次调用）

```text
你是好特卖门店的设备报修填报助手，引导店员用最短路径完成报修单。
【采集范围】只允许采集以下表单字段：报修类别、报修详情（故障表现/产生原因/导致后果）、联系人、手机号码、问题照片或视频。
【禁止】1) 询问表单以外信息（如故障发生时间、天气、客流）；2) 编造字典外的枚举值；3) 在用户确认前生成提交动作；4) 一轮追问多个问题。
【风格】口语、简短、每次只处理一件事。所有输出按 reply_schema 返回 JSON。
```

### 4.2 类别识别（A_WAIT，GLM-5.3）

```text
## 任务
从店员的问题描述中识别报修类别。只能从 <类别字典> 中选择，禁止输出字典外的值。

## 类别字典（由工具 get_category_dict 注入）
{category_dict}

## 用户描述
{user_text}

## 输出 JSON Schema
{"candidates": [{"name": "字典内类别", "confidence": 0.0}], "need_detail": true}

## 示例
描述："店里的冷藏风幕柜不制冷了，温度显示15度，鲜奶都下架了"
输出：{"candidates": [{"name": "制冷设备 - 风幕柜/立式冷柜", "confidence": 0.92},
                     {"name": "制冷设备 - 卧式冰柜", "confidence": 0.31}], "need_detail": true}
```

**置信度分流阈值**：≥0.75 预选置顶只出 1+2 卡；0.40~0.75 出 Top3 卡；<0.40 进 `FALLBACK` 全量点选。阈值上线后按"类别识别准确率"埋点数据校准。

### 4.3 三要素抽取与润色（DETAIL，GLM-5.3）

```text
## 任务
从描述中抽取报修详情三要素，并润色为规范短句。要素缺失时对应字段返回 null，并生成一句"仅针对缺失要素"的追问。
## 输出 JSON Schema
{"表现": "…", "原因": "…|null", "后果": "…|null", "追问": "…|null"}
## 边界
- 用户口语原意优先，不添加未提及的事实（原因常为"初步判断"）
- "追问"只能是：表现 / 原因 / 后果 三者之一的补齐请求
```

---

## 5. 结构化输出与防幻觉

| 环节 | 机制 |
|---|---|
| 输出格式 | GLM response_format=json_object，解析失败重试 1 次，再失败进降级 |
| 枚举校验 | LLM 候选逐个与字典比对：不在字典内 → 直接丢弃；丢弃后为空 → `FALLBACK` |
| 置信度 | 仅用于分流 UI（出几张卡），不作为跳过用户确认的依据——**A 分支必须点选** |
| 详情校验 | 三要素 schema 校验；润色结果进确认单前与原文一并展示（✏️ 可改） |
| 提交校验 | `CONFIRM` 前端 + 服务端双重校验 13 字段必填，缺失则拒绝进入 `DONE` |

---

## 6. 会话与草稿

Redis 结构（Key 前缀 `rp:{session_id}`，TTL 7 天）：

```json
{
  "session_id": "s_xxx", "user_id": "u_1001", "store_no": "SH0021",
  "state": "PHOTO", "branch": "B",
  "collected_fields": {
    "category": "收银设备 - 收银机/扫码枪故障",
    "detail": {"表现": "…", "原因": "…", "后果": "…"},
    "photos": ["m_001", "m_002"],
    "contact": {"name": "王芳", "phone": "+86139****0000"},
    "brought": {"region": "华东大区", "store": "…", "addr": "…", "hours": "…"}
  },
  "updated_at": "2026-10-31T12:00:00Z"
}
```

断点续填：每次状态变更即 `draft_save`；重进会话 `draft_get` 恢复到上次状态。与老表单草稿**不同 Key 空间**，天然隔离、互不覆盖；切老表单时不回写（本期），仅在确认单展示带出字段。

---

## 7. 前后端接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/sessions` | POST | 建会话，返回 session_id + 首屏消息（状态条 + Top6 卡片） |
| `/api/sessions/{id}/messages` | POST | 用户消息（文本/卡片点击/上传完成），响应 **SSE 流**：typing → 文本 → 卡片消息 |
| `/api/sessions/{id}` | GET | 恢复会话（断点续填/换端） |
| `/api/sessions/{id}/submit` | POST | CONFIRM 状态提交，调 `create_repair_order` |

**消息协议**（SSE `data`，与原型组件一一对应）：

| type | 载荷 | 对应原型组件 |
|---|---|---|
| `text` | 文本 | 气泡 |
| `quick_cards` | 卡片数组 | Top6 高频卡片 |
| `enum_card` | 选项数组 + 预选标记 | 枚举单选卡 |
| `photo_guide` | 清单 + 是否强校验 | 拍摄指引（购物袋红色警示） |
| `contact_confirm` | 默认联系人 | 用本人/修改按钮 |
| `confirm_card` | 13 字段确认单 | 📋 预填确认单 |
| `result` | 单号、状态 | 提交成功卡 |
| `typing` / `system_tip` | — | 输入态 / 居中提示 |

---

## 8. 埋点清单（对应度量指标）

| 事件 | 触发点 | 支撑指标 |
|---|---|---|
| `session_start` / `session_end` | 建会话 / 提交或退出 | 填报时长（end-start）、步数（用户消息计数） |
| `branch_a_input` / `branch_b_card_click` | 两个分支入口 | 分支占比、渗透率分母 |
| `enum_first_pick` | A 分支：AI 预选项是否被直接点选 | **类别识别准确率**（first_pick=true 占比） |
| `fallback_enter` / `switch_to_form` | 降级 / 切老表单 | 兜底率、转老表单率 |
| `photo_reject` | 拍摄清单缺项 | 防驳回前置拦截量 |
| `contact_change` | 修改联系人 | 代报占比 |
| `submit_success` / `submit_fail` | 明道云返回 | 完成成功率、卡壳定位 |
| 上线前 | 导出老表单 2 周数据 | 时长/驳回率基线 |

> 对账口径：AI 单量渗透率 = `submit_success` 数 / 明道云总报修单量（后台取数）。

---

## 9. 工程降级

| 故障 | 处理链 |
|---|---|
| LLM 超时（>8s） | 重试 1 次 → 仍失败：会话提示"稍后再试"，给出 Top6 卡片（纯规则可用） |
| LLM 整体不可用 | 状态机降级为"卡片向导"模式：点卡片→规则追问→老表单式补齐，**不阻塞报修** |
| 识别/解析失败 | `FALLBACK` 全量点选，永远有路可走 |
| 明道云提交失败 | 本地落 MySQL 待发表 + 定时重试 3 次 → 仍失败转人工通道并通知用户 |
| SSE 断线 | 前端重连按 `GET /sessions/{id}` 恢复，消息不丢（服务端可重放当前状态） |
| Redis 故障 | 会话降级为内存 + 提交前强校验，牺牲断点续填不影响成交 |

---

## 10. 演进设计

| 阶段 | 形态 | 复用点 |
|---|---|---|
| 本期 | 单体 Agent + 状态机（报修） | 工具层 MCP 与业务解耦 |
| 二期 | + 报销业务 | 换 Prompt 组 + 字典 + 工具子集（OCR 发票抽取），状态机框架复用 |
| 终态 | 多 Agent：意图路由 / 抽取 / 质检审核（驳回侧） | 本期即按 LangGraph 的 supervisor 模式约束状态边界：每个状态 = 未来一个节点的候选；工具已全部 MCP 化，天然可被多 Agent 共享 |

**为拆分预留的三件事**：状态机转移表配置化（不写死 if-else）；Prompt 版本化管理（PM 层）；LLM 调用与业务字段解耦（输入输出全部 schema 化）。

---

## 附：关键代码骨架

```python
# 1. 状态机引擎（转移表配置化）
TRANSITIONS = {
    "INIT":      {"text": "A_WAIT", "card": "B_LOCK"},
    "A_WAIT":    {"hi_conf": "A_ENUM", "lo_conf": "FALLBACK"},
    "A_ENUM":    {"pick": "DETAIL"},
    "B_LOCK":    {"auto": "DETAIL"},
    "DETAIL":    {"ok": "PHOTO", "missing": "DETAIL"},
    "PHOTO":     {"pass": "CONTACT", "lack": "PHOTO"},
    "CONTACT":   {"confirm": "CONFIRM"},
    "CONFIRM":   {"submit": "DONE", "recat": "PHOTO"},
}
def step(state, event, ctx):
    nxt = TRANSITIONS[state].get(event)
    if nxt: run_state_handler(nxt, ctx); ctx.state = nxt; draft_save(ctx)
    return nxt

# 2. MCP 工具注册（fastmcp）
@mcp.tool()
def get_category_dict(store_no: str = "") -> dict:
    """返回类别树、Top6 高频卡片文案、各类别拍摄清单"""
    return dict_repo.get(store_no)

# 3. 类别识别 + 防幻觉校验
def recognize(text, dict_list):
    out = llm_json(CAT_PROMPT, user_text=text, category_dict=dict_list)   # GLM-5.3
    valid = [c for c in out["candidates"] if c["name"] in set(dict_list)]  # 枚举白名单过滤
    if not valid: return fallback_full_list(dict_list)
    return valid[:3]
```

> **TODO（阻塞项，对应 11.4 评审前置依赖）**：① 真实报修类别枚举字典及"卡片文案↔枚举"映射表；② 明道云 API 文档与字段映射（13 字段 → 明道云表字段）；③ 员工档案 / 门店主数据接口权限。
