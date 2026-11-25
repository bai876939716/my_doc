"""报修技能包 · 包声明"""
from app.core.skill_contract import SkillDef, QuickCard

SKILL = SkillDef(
    name="repair",
    url="repair",
    session_prefix="rp_",
    title="AI 报修助手",
    open_talk="repair.open",
    context_tool="get_user_context",     # 建会话轮：带出门店/报修人/日期/手机号
    submit_tool="create_repair_order",   # 提交轮：幂等建单
    summary_slot="category",             # 归档摘要表 category 列=报修类别（漏斗分析按它分桶）
    boot_params=("store_no", "reporter_name", "reporter_phone"),
    # store_no：门店编号（一人多店）→ get_user_context(store_no=...)
    # reporter_name/reporter_phone：Java 转发层从网关 token 解出的姓名/手机号（X-Reporter-* 头，
    # API 层解码后注入 config）——user_id 查不到 base-shop 员工档案，靠这个带出报修人
    # 快捷卡片按线上 2.8 万单频次定稿(2026-11-24,报告见 my_doc 实施/类别分布分析):
    # 桶内分不清的(照明灯两档/摄像头系)值给候选列表,点卡后再让用户点一下细分;
    # 摄像头合并一张卡,空出的位补回全量第 5 的灭火器
    quick_cards=[
        QuickCard(id="c_light",  icon="💡", title="照明灯不亮",     value={"category": ["工程类 - 照明灯（1至4个）", "工程类 - 照明灯（超过4个）"]}),
        QuickCard(id="c_sign",   icon="🪧", title="门头/招牌灯不亮", value={"category": "工程类 - 门头字/LOGO字不亮"}),
        QuickCard(id="c_switch", icon="🔌", title="开关/插座/线路",  value={"category": "工程类 - 开关 / 插座 / 线路"}),
        QuickCard(id="c_cool",   icon="❄️", title="冷柜不制冷",      value={"category": "工程类 - 风幕柜/冷藏柜（过保）（门店自修）"}),
        QuickCard(id="c_cam",    icon="📷", title="摄像头问题",      value={"category": ["工程类 - 摄像头-离线（已重启仍失败）", "工程类 - 摄像头-其他问题", "工程类 - 摄像头-有内存卡但无回放（已格式化仍失败）"]}),
        QuickCard(id="c_fire",   icon="🧯", title="灭火器",          value={"category": "工程类 - 灭火器（门店自修）"}),
    ],
)
