# app/ · 技能包文件化实现（真实代码）

> 这不是伪代码示例，是**可直接开工的技能包实现**：类型真实（dataclass）、加载真实（`load()` 自检可跑）、卡片组装真实（输出对齐接口文档）。外部依赖（档案/明道云/OSS/LLM）全部是带 ⚠️ 标记的 Mock 顶替位，签名即 mcpServer 接口形状。

## 结构（= agent-service-center 的目录约定）

```
app/
├── core/
│   └── skill_contract.py      # 契约：SlotType/Source/钩子/Slot/SkillDef —— 引擎与技能包的全部接口
└── skills/
    └── repair/                # 报修技能包（五件套 + 两个实现模块）
        ├── __init__.py        # load()：组装五件套 + 启动时自检（缺话术/缺字典/重名即刻暴露）
        ├── skill.py           # ① 包声明：name/url/前缀/高频卡
        ├── slots.py           # ② 字段 schema：10 槽位 + 钩子声明（声明顺序=议程问序）
        ├── dict_sources.py    # ③ 字典源：缓存 key/TTL/回源工具绑定 + 真值示例
        ├── talks.py           # ④ 话术模板：13 组，0 LLM
        ├── prompts.py         # 系统词动态注入片段 + 抽取输出 schema
        ├── tools.py           # ⑤ 工具：4 个（Mock 顶替位）
        └── cards.py           # 消息组装：State 快照 → 接口文档 §2.2 的 10 种卡片报文
```

## 验证（已跑通）

```bash
cd 实现细节
python3 -c "from app.skills import repair; repair.load()"     # 加载自检
python3 -c "from app.skills.repair import cards; ..."         # 卡片组装（见 README 外的验证输出）
```

自检覆盖：槽位重名、开场话术存在、字典已注册、钩子类型合法；组装验证覆盖 status_bar/enum_card/confirm_card 的字段与 source 枚举对齐接口文档。

## 与 agent-service-center 的关系

这套文件就是仓库里 `app/core/` 与 `app/skills/repair/` 的设计真值：POC 时整体拷入（或按此重写），引擎节点（load_context/extract/update/route/ask/confirm/submit/fallback）按 `skill_contract` 的类型读取 `load()` 的产物。报销接入 = 新建 `app/skills/expense/` 同构目录，core 零改动。

## 设计说明

- 契约在 core、实现技能包内——业务侧 import 契约做声明，不写流程代码；
- 钩子是**可组合的规则对象**（MustPick/OnChange/Derive/DefaultFill/SubmitCheck），update 节点按序执行；
- `SkillRuntime` 是引擎注入的只读视图，钩子通过它读现场，不直接摸 State；
- 外部依赖的 Mock 全部集中在 tools.py 一个文件，替换不扩散。
