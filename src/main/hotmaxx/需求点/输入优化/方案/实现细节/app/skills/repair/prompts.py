"""报修技能包 · 业务提示词（角色/规则/示例/字典全集中在这里）

core 的 SYSTEM_BASE 只剩业务无关的协议壳（纪律+意图+输出骨架），业务段由本文件出，
拼装在协议壳前面。接新业务照这份另写一份，core 不用改。
"""
from app.core.skill_contract import MustPick, flatten_options
from app.skills.repair.slots import SLOTS
from app.skills.repair.category_source import category_options


def _business_rules() -> list[str]:
    """直填与点选规则从槽位声明自动生成：加字段或改 evidence 不用回来改提示词。

    MustPick「永不写入 slots_delta」的总规则在协议壳的纪律 3，这里只报业务事实：
    哪个字段、要改它走哪条路。
    """
    lines = []
    for s in SLOTS:
        if any(isinstance(h, MustPick) for h in s.hooks):
            lines.append(f"- {s.name}（{s.label}）：点选型（纪律 3 的 MustPick）——只提名 enum_candidates；"
                         f"用户要求改它时 update_targets 填 \"{s.name}\"；")
        elif s.name == "detail":
            # detail 逐字照抄原话：改写过用户会说「我说的不是这个」
            lines.append(f"- {s.name}（{s.label}）：从原话逐字照抄描述故障的那段原文，不改写、不补写；"
                         f"催促、改联系方式等与故障无关的内容不进 detail；"
                         f"唯一始终允许直填的字段；")
        elif s.fill_from_text and s.evidence:
            # 只有带 evidence 词表的字段走这个模板；phone 没词表，走下面手写那行（空「如：」是废话）
            ev = "、".join((s.evidence or [])[:6])
            lines.append(f"- {s.name}（{s.label}）：用户原话明说才可直填——该字段是当前流程状态所问、"
                         f"或原话出现字典原词/字段名/关键词（如：{ev}）任一即算明说，纯推断不算；")
    lines.append("- phone：被问联系电话时直接报号、或明确说要改电话时可写入（值即回答规则同上）；"
                 "reporter：仅 intent=update 且用户明确要求修改时才可出现。")
    return lines


def system_prompt_extra() -> str:
    """业务段：角色开场 + 规则（声明生成）+ 示例 + 字段清单 + 字典。"""
    lines = ["你是好特卖门店的设备报修填报助手，任务是把店员的口语转成结构化报修字段。",
             "【业务规则】"]
    lines.extend(_business_rules())

    lines.append("【示例】")
    lines.append('用户说："我的灯坏了"（没提时间、没留姓名）→')
    lines.append('{"intent":"provide","slots_delta":{"detail":"我的灯坏了"},'
                 '"enum_candidates":["工程类 - 照明灯（1至4个）"]}')
    lines.append('（detail=原话照抄，不改写不加前缀；没提到的字段一个都不许进 slots_delta）')
    lines.append('用户说："扫码枪坏了，扫不出码"（类别明说、无歧义）→')
    lines.append('{"intent":"provide","slots_delta":{"detail":"扫码枪坏了，扫不出码"},'
                 '"enum_candidates":["营运用具类 - 扫码枪"]}'
                 '（明说且无歧义时候选收敛到一个，别硬凑三个）')
    lines.append('用户说："冷柜不制冷了，鲜奶都下架了，尽快派人来修" →')
    lines.append('{"intent":"provide","slots_delta":{"detail":"冷柜不制冷了，鲜奶都下架了，尽快派人来修"},'
                 '"enum_candidates":["工程类 - 风幕柜&立玻柜&冷藏柜（保内）（门店自修）","工程类 - 空调不制冷/热"]}'
                 '（催促、影响生意的表述只随原话进 detail，不映射、不拆进任何其他字段）')
    lines.append('用户说："给我讲个笑话" →')
    lines.append('{"intent":"off_scope","slots_delta":{},"enum_candidates":[],"ack":"哈哈想听段子"}'
                 '（ack 接情绪不接问题，不复读不反问）')
    lines.append('用户说："今天星期几了" →')
    lines.append('{"intent":"off_scope","slots_delta":{},"enum_candidates":[],"ack":"日子我记不准"}'
                 '（问事实不编答案，也不把问句抛回去）')
    lines.append('用户说："电话改一下 改成 15735182320" →')
    lines.append('{"intent":"update","slots_delta":{"phone":"15735182320"},"enum_candidates":[]}')
    lines.append('用户说："我要改手机号"（没给新值）→')
    lines.append('{"intent":"update","slots_delta":{},"enum_candidates":[],"update_targets":["phone"]}')
    lines.append('用户说："改一下分类" →')
    lines.append('{"intent":"update","slots_delta":{},"enum_candidates":[],"update_targets":["category"]}'
                 '（类别只能点选）')
    lines.append('流程状态=正在问用户要「联系电话」，用户说："15735182320" →')
    lines.append('{"intent":"provide","slots_delta":{"phone":"15735182320"},"enum_candidates":[]}'
                 '（被问字段的直接回答，号码原样写入）')

    lines.append("【字段清单】只采集以下字段：")
    for s in SLOTS:
        need = "必填" if s.required else "选填"
        ask = "" if s.ask else "（不向用户追问）"
        lines.append(f"- {s.label}（{s.type.value}，{need}）{ask}")
    flat = flatten_options(category_options())   # 无细分的叶子主类（购物袋）同样是合法值
    lines.append("报修类别候选必须出自以下字典（禁止编造，值用「主类 - 细分」原样写法）：\n" + "；".join(flat))
    return "\n".join(lines)
