"""报修技能包 · 字典源（缓存结构 + 回源工具绑定）"""
from app.core.skill_contract import ToolSpec

# Redis 缓存 key 规则：dict:{skill}:{dict_name}，TTL 3600，miss 时调 refresh_tool 回源
DICTS = {
    "repair_category": {
        "cache_key": "dict:repair:category",
        "ttl": 3600,
        "refresh_tool": "get_category_dict",
        # options 形状：[(主类, [细分...]), ...] —— ❗真值以明道云实际配置为准（TODO#3）
        "options": [
            ("工程类",     ["制冷设备/冷柜", "暖通/空调", "照明/招牌", "水电/下水道", "货架/门锁"]),
            ("运营用具类", ["收银/扫码设备", "购物袋/物料"]),
            ("特别紧急",   ["影响营业的紧急故障"]),
        ],
        # 拍摄清单：photos 槽位规则校验用（比对清单，不调 LLM）
        "shots": {
            "制冷设备/冷柜": ["设备整体", "问题部位特写"],
            "购物袋/物料":   [("问题部位", "info"), ("袋底供应商标识（必拍）", "warn")],
            "default":       ["问题部位特写"],
        },
    },
    "user_context": {                      # 带出字段来源（非卡片字典）
        "tool": "get_user_context",
        "fields": ["store", "reporter", "date", "phone"],
    },
}
