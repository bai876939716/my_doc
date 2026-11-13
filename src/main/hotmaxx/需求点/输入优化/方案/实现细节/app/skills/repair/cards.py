"""报修技能包 · 消息组装（State 快照 → 卡片消息的 data 结构）

输出的 type 与 data 字段严格对齐《接口文档.md》§2.2 的 10 种服务端消息。
引擎 ask/confirm 节点调用这里的函数，不自己拼报文。
"""


def status_bar(ctx: dict) -> dict:
    return {"type": "status_bar",
            "data": {"store": ctx["store"], "reporter": ctx["reporter"],
                     "phone_masked": ctx["phone_masked"]}}


def quick_cards(skill) -> list[dict]:
    return {"type": "quick_cards",
            "data": {"cards": [{"id": c.id, "icon": c.icon, "title": c.title}
                               for c in skill.quick_cards]}}


def enum_card(slot: str, options: list[str], preselect: int, mode: str = "top3") -> dict:
    return {"type": "enum_card",
            "data": {"field": slot, "mode": mode, "options": options, "preselect": preselect}}


def photo_guide(tips: list, warning: str | None, need_count: int) -> dict:
    return {"type": "photo_guide",
            "data": {"need_count": need_count, "tips": tips, "warning": warning}}


def seg_card(slot: str, question: str, options: list[tuple[str, str]]) -> dict:
    return {"type": "seg_card",
            "data": {"field": slot, "question": question,
                     "options": [{"value": v, "label": l} for v, l in options]}}


def phone_confirm(masked: str) -> dict:
    return {"type": "phone_confirm", "data": {"phone_masked": masked, "use_default": True}}


def confirm_card(state) -> dict:
    """10 字段确认单：从 State 组装，全部可编辑，带 source。"""
    def item(field, label, value, source):
        return {"field": field, "label": label, "value": value,
                "editable": True, "source": source}
    ctx, s = state["context"], state["slots"]
    def v(n): return s[n]["value"]
    def src(n): return s[n]["source"]
    return {"type": "confirm_card", "data": {"groups": [
        {"group_name": "基础信息（系统带出）", "items": [
            item("store", "门店名称", ctx["store"], "system_default"),
            item("reporter", "报修人员", ctx["reporter"], "system_default"),
            item("date", "报修日期", ctx["date"], "system_default"),
        ]},
        {"group_name": "报修内容（AI 采集整理）", "items": [
            item("category", "报修类别", v("category"), src("category")),
            item("detail", "描述具体故障", v("detail"), src("detail")),
            item("photos", "现场照片", f"{len(v('photos'))} 张", src("photos")),
            item("expect_visit", "期望上门", v("expect_visit"), src("expect_visit")),
            item("impact_business", "影响营业", v("impact_business"), src("impact_business")),
            item("phone", "联系电话", ctx["phone_masked"], "system_default"),
            item("expect_process", "期望处理（选填）", v("expect_process") or "", "system_default"),
        ]},
    ], "missing": []}}


def result(order: dict, tip: str = "可在报修记录查看处理进度") -> dict:
    return {"type": "result",
            "data": {"order_no": order["order_no"], "status": order["status"], "tip": tip}}
