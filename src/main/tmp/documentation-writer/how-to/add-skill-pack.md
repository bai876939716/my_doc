# 如何:新增一个业务技能包

**适用场景**:在现有部署单元上接入新业务(如报销、巡检)。设计承诺:框架层 `app/core/` **零改动**,只需新增技能包 + 挂一份同形状路由。`tests/test_neutrality.py` 用假技能包守着这条线——改坏了 core 的业务中立性,测试会红。

## 步骤

1. **建技能包目录** `app/skills/<bizname>/`,至少包含包声明(参照 `app/skills/repair/skill.py`):

   ```python
   from app.core.skill_contract import SkillDef, QuickCard

   SKILL = SkillDef(
       name="<bizname>",           # 唯一名,healthz 的 skills 列表来自它
       url="<bizname>",            # 路由 URL 段:/agent/<bizname>/v1/**
       session_prefix="<prefix>_", # 会话 ID 前缀,避免跨业务撞车
       title="…",
       open_talk="<biz>.open",     # 开场话术键(talks 里要有对应条目)
       context_tool="…",           # 建会话轮取上下文的工具名
       submit_tool="…",            # 提交轮建单的工具名(须幂等)
       summary_slot="…",           # 归档摘要列(漏斗分析分桶字段)
       boot_params=(…),            # 建会话入口额外带的参数
       quick_cards=[QuickCard(...)],  # 快捷卡片(可为空列表)
   )
   ```

2. **补齐声明式配置**:槽位定义(`slots.py`,字段 schema + 字典来源 + 手势)、字典(`dict_sources.py`)、话术(`talks.py`)、工具(`tools.py`)、提示词(`prompts.py`)、卡片(`cards.py`)。校验口径全读这些声明,路由层不会再抄一份字段清单。

3. **挂路由**:在 `app/api/` 下为该技能包挂一份与 `routes.py` 同形状的路由(前缀 `/agent/<bizname>/v1`)。

4. **注册到入口**:`app/main.py` 里 `repair.load()` 旁边加载新包,进 `app.state.skill_packs`。

5. **验证**:

   ```bash
   pytest -v                       # 含 test_neutrality(框架中立性)与全量回归
   uvicorn app.main:app --port 8000
   curl localhost:8000/healthz     # skills 列表应出现新包名
   ```

## 常见问题

**启动即拒?** 启动自检会拦技能包配置问题:槽位重名、缺话术、缺字典——报错信息指明缺什么,补齐即可(`app/main.py` lifespan 中 `<pack>.load()`)。

**点选校验不认我的新字段?** 检查槽位声明的 `card` 形态(enum_card/seg_card)与 `dict_source` 白名单是否配对——路由校验按 `(field, card)` 精确匹配(来源:`app/api/routes.py` `IncomingMsg.validate_message`)。
