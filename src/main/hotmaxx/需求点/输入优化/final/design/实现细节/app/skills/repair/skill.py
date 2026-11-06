"""报修技能包 · 包声明"""
from app.core.skill_contract import SkillDef, QuickCard

SKILL = SkillDef(
    name="repair",
    url="repair",
    session_prefix="rp_",
    title="AI 报修助手",
    open_talk="repair.open",
    quick_cards=[
        QuickCard(id="c_cool",  icon="❄️", title="冷柜/空调不制冷",  value={"category": "工程类 - 制冷设备/冷柜"}),
        QuickCard(id="c_pos",   icon="💻", title="收银机/扫码枪故障", value={"category": "运营用具类 - 收银/扫码设备"}),
        QuickCard(id="c_light", icon="💡", title="照明/招牌灯不亮",   value={"category": "工程类 - 照明/招牌"}),
        QuickCard(id="c_bag",   icon="🛍️", title="购物袋/物料质量",   value={"category": "运营用具类 - 购物袋/物料"}),
        QuickCard(id="c_water", icon="🚰", title="水电/下水道",       value={"category": "工程类 - 水电/下水道"}),
        QuickCard(id="c_shelf", icon="🔧", title="货架/门锁",        value={"category": "工程类 - 货架/门锁"}),
    ],
)
