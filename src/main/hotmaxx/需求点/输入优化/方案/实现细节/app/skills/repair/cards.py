"""报修技能包 · 消息组装（State 快照 → 卡片消息的 data 结构）

type 和 data 字段是前端契约，改这里要同步改接口文档。
引擎调用点：status_bar/quick_cards（建会话首屏）、confirm_card（confirm 节点）、result（出单）；
采集类卡片（enum_card/seg_card/photo_guide/phone_confirm）由引擎 ask 节点现拼，不在本文件。
"""
from app.core.redact import mask_phone


def status_bar(ctx: dict) -> dict:
    return {"type": "status_bar",
            "data": {"store": ctx["store"], "reporter": ctx["reporter"],
                     "phone_masked": ctx["phone_masked"]}}


def quick_cards(skill) -> list[dict]:
    return {"type": "quick_cards",
            "data": {"cards": [{"id": c.id, "icon": c.icon, "title": c.title}
                               for c in skill.quick_cards]}}


def confirm_card(state) -> dict:
    """确认单以槽位为准：store/date 不给改，其余条目带来源。

    这里的 label 是 slots.py 声明的手抄副本，改那边记得改这里。
    """
    def item(field, label, value, source):
        return {"field": field, "label": label, "value": value,
                "editable": field not in ("store", "date"), "source": source}
    ctx, s = state["context"], state["slots"]
    def v(n): return s[n]["value"]
    def src(n): return s[n]["source"]
    return {"type": "confirm_card", "data": {"groups": [
        {"group_name": "基础信息（系统带出）", "items": [
            item("store", "门店名称", v("store"), src("store")),
            item("reporter", "报修人员", v("reporter"), src("reporter")),
            item("date", "报修日期", v("date"), src("date")),
        ]},
        {"group_name": "报修内容（AI 采集整理）", "items": [
            item("category", "报修类别", v("category"), src("category")),
            item("detail", "故障情况", v("detail"), src("detail")),
            item("photos", "现场照片", v("photos"), src("photos")),
            item("phone", "联系电话", mask_phone(v("phone")), src("phone")),
        ]},
    ], "missing": []}}


def result(order: dict, tip: str = "可在报修记录查看处理进度") -> dict:
    return {"type": "result",
            "data": {"order_no": order["order_no"], "status": order["status"], "tip": tip}}
