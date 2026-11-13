"""报修技能包 · 包加载入口（core 引擎按此协议读取技能包）

引擎侧使用方式：
    from app.skills import repair
    pack = repair.load()          # → dict，包含五件套 + 加载校验
"""
from app.skills.repair.skill import SKILL
from app.skills.repair.slots import SLOTS, STATIC_DICTS, SUBMIT_CHECK
from app.skills.repair.dict_sources import DICTS
from app.skills.repair.tools import TOOLS
from app.skills.repair.talks import TALKS
from app.core.skill_contract import TalkEntry


def load() -> dict:
    """组装五件套并做加载自检（缺话术/缺字典/槽位重名 在启动时暴露，不拖到运行时）。"""
    names = [s.name for s in SLOTS]
    assert len(names) == len(set(names)), "槽位重名"
    assert SKILL.open_talk in TALKS, f"开场话术缺失: {SKILL.open_talk}"
    for s in SLOTS:
        if s.dict_source and not s.dict_source.startswith("static:"):
            assert s.dict_source in DICTS, f"{s.name} 字典未注册: {s.dict_source}"
    return {
        "def": SKILL,
        "slots": SLOTS,
        "dicts": {**DICTS, **{f"static:{k}": {"options": v} for k, v in STATIC_DICTS.items()}},
        "tools": TOOLS,
        "talks": TALKS,
        "submit_check": SUBMIT_CHECK,
    }
