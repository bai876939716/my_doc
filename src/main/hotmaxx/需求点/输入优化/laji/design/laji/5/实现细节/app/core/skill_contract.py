"""技能包契约 · core 侧定义（引擎与技能包之间的全部接口）

「技能包 = 数据，引擎 = 读者」——本文件定义技能包五件套的类型形状。
业务侧只 import 这些类型做声明，不写流程代码。
"""
from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable


class SlotType(str, Enum):
    CONTEXT = "context"      # 工具带出，不问
    ENUM = "enum"            # 枚举/下拉：LLM 给 Top3 → 必须点选
    TEXT = "text"            # 自由文本：LLM 抽取 + 范式整理
    MEDIA = "media"          # 照片/附件：上传 + 清单规则校验
    TREE = "tree"            # 树筛选（报销）：路径点选
    MONEY = "money"          # 金额（报销）
    DATE = "date"            # 日期（报销）
    GROUP = "subgroup"       # 字段组（报销：收款人四件套）
    TABLE = "subtable"       # 子表（报销：费用明细）
    COMPUTED = "computed"    # 程序计算，不问不抽


class Source(str, Enum):
    SYSTEM = "system_default"   # 带出
    AI = "ai_filled"            # AI 预填
    USER = "user_edited"        # 用户点选/修改


# ---------- 钩子（可组合的规则对象，update 节点按序执行） ----------

@dataclass
class MustPick:
    """enum/tree 候选不直接入库：extract 产出只进卡片消息，点选事件才写槽。"""
    pass


@dataclass
class OnChange:
    """本槽位值变化时，把 invalidates 列出的槽位标失效（清值、塞回议程）。"""
    invalidates: list[str]

@dataclass
class Derive:
    """本槽位由 from_slot 推导（如一级部门 = 部门路径[0]），不进议程。"""
    formula: Callable[[Any], Any]

@dataclass
class DefaultFill:
    """when 条件成立且值为空时（如卡片路径进 confirm），自动填 value。"""
    when: Callable[["SkillRuntime"], bool]
    value: Callable[["SkillRuntime"], str]

@dataclass
class SubmitCheck:
    """submit 前：必填齐 + 技能包自定义校验，返回错误消息或 None。"""
    rule: Callable[["SkillRuntime"], str | None]


# ---------- 五件套 ----------

@dataclass
class Slot:
    name: str                          # 槽位名（State.slots 的键）
    type: SlotType
    label: str                         # 确认单/话术里的显示名
    required: bool = True
    ask: bool = True                   # False = 不进议程（context/computed/选填）
    dict_source: str | None = None     # 字典名 → dict_sources 注册表里查
    source_field: str | None = None     # context 槽：从工具返回里取哪个字段（默认=槽位名）
    card: str | None = None            # 卡片形态（enum_card / seg_card / photo_guide...）
    default: Any = None
    pattern: str | None = None         # text 范式说明（注入 Prompt）
    max_len: int | None = None
    predict: bool = False              # extract 是否预判
    check_list: str | None = None      # media：按字典里的拍摄清单做规则校验
    hooks: list[Any] = field(default_factory=list)


@dataclass
class QuickCard:
    id: str
    icon: str
    title: str
    value: dict[str, Any]              # 点卡直填的槽位值（0 LLM）


@dataclass
class TalkEntry:
    key: str
    text: str                          # 支持 {slot_name} 占位


@dataclass
class ToolSpec:
    name: str                          # mcpServer 工具名（ToolProvider 注册）
    purpose: str


@dataclass
class SkillDef:
    """技能包声明 —— app/skills/<name>/skill.py 的 SKILL 常量。"""
    name: str                          # "repair"
    url: str                           # /agent/{url}/v1
    session_prefix: str                # rp_ / ex_
    title: str                         # 会话页标题
    open_talk: str                     # 开场话术 key（talks 里查）
    quick_cards: list[QuickCard] = field(default_factory=list)


# 引擎在运行时注入的只读视图（技能包钩子用它读现场，不直接摸 State）
class SkillRuntime:
    """由 core 构造：get(slot) / context(key) / card_mode 等只读方法。"""
    pass
