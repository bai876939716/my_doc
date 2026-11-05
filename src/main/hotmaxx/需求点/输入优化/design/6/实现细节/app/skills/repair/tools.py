"""报修技能包 · 工具实现（ToolProvider 注册的进程内实现）

四个工具：带出 / 字典 / 上传 / 建单。签名即 mcpServer 接口形状，
Mock 依赖打 ⚠️ 标记——POC/联调时替换为真实调用，签名不变。
"""

def get_user_context(uid: str, store_no: str | None = None) -> dict:
    """带出 3+1 字段：门店/报修人/日期/手机号（掩码）。
    ⚠️ 依赖：员工档案 + 门店主数据（接口未确认前，由网关注入的 uid 信息顶替）。"""
    return {
        "store": "11522 安徽商之都OK店",       # ⚠️ mock：真实实现查门店主数据
        "reporter": "白云翔",
        "date": "2026-11-05",
        "phone": "13800001234",
    }


def get_category_dict() -> dict:
    """类别树 + 拍摄清单。读 Redis 缓存 dict:repair:category，miss 回源明道云选项集。"""
    from app.skills.repair.dict_sources import DICTS
    return {                      # ⚠️ mock：真实实现 = cache.get_or_refresh(...)
        "options": DICTS["repair_category"]["options"],
        "shots": DICTS["repair_category"]["shots"],
    }


def upload_media(files: list) -> list[str]:
    """照片/视频上传 OSS → media_ids。本体不进 State，只存引用。"""
    return [f"m_{i+1:03d}" for i in range(len(files))]   # ⚠️ mock：OSS 上传


def create_repair_order(order_key: str, slots: dict, context: dict) -> dict:
    """明道云建单（单账号服务端代理）。
    order_key = 会话 ID：重复提交返回同一单号（幂等由明道云侧按 key 去重，上线前必须验证）。"""
    return {                                              # ⚠️ mock：明道云 API + 字段映射
        "order_no": "BX-20261105-0007",
        "status": "已提交 / 待派单",
    }


TOOLS = [get_user_context, get_category_dict, upload_media, create_repair_order]
