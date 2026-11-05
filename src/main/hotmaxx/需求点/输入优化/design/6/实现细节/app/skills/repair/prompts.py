"""报修技能包 · Prompt 注入片段（系统词动态生成，不写死字段清单）"""
from app.skills.repair.slots import SLOTS
from app.skills.repair.dict_sources import DICTS


def system_prompt_extra() -> str:
    """拼进系统提示词的技能包片段：字段清单（由 slots 声明生成）+ 类别字典拍平。"""
    lines = ["本轮业务：设备报修。只采集以下字段："]
    for s in SLOTS:
        need = "必填" if s.required else "选填"
        ask = "" if s.ask else "（不向用户追问）"
        lines.append(f"- {s.label}（{s.type.value}，{need}）{ask}")
    flat = [f"{main}-{sub}" for main, subs in DICTS["repair_category"]["options"] for sub in subs]
    lines.append("报修类别候选必须出自以下字典（禁止编造）：\n" + "；".join(flat))
    return "\n".join(lines)


EXTRACT_OUTPUT_SCHEMA = {
    "intent": "provide|update|query_progress|smalltalk|off_scope|inject_attempt",
    "slots_delta": {
        "detail": "str（按范式整理，≤100字）",
        "impact_business": "是|否（预判，仅作候选）",
    },
    "category_candidates": "list[str] ≤3（字典内，白名单过滤后）",
    "detail_structured": {"现象": "str", "时间": "str", "影响": "str"},
}
"""联合抽取输出 schema：报修模式 = 意图 + 槽位增量 + 类别候选 + 描述整理 + 影响预判。
报销技能包接入时在各自 prompts.py 里扩展（rows_draft / tree_candidates 等），互不影响。"""
