"""报修技能包 · 10 槽位声明（声明顺序 = 议程问序）"""
from app.core.skill_contract import (
    Slot, SlotType, MustPick, OnChange, DefaultFill, SubmitCheck,
)

SLOTS = [
    # ---- 带出组：不进议程，状态条展示 ----
    Slot(name="store",    type=SlotType.CONTEXT, label="门店名称", required=True, ask=False,
         dict_source="user_context", ),
    Slot(name="reporter", type=SlotType.CONTEXT, label="报修人员", required=True, ask=False,
         dict_source="user_context", ),          # 店长替店员报修可改（确认单 editable）
    Slot(name="date",     type=SlotType.CONTEXT, label="报修日期", required=True, ask=False,
         default="today"),

    # ---- 采集组：进议程 ----
    Slot(name="category", type=SlotType.ENUM, label="报修类别", required=True,
         dict_source="repair_category", card="enum_card",
         hooks=[MustPick(),                                  # AI 只提名，点选才入库
                OnChange(invalidates=["photos", "detail"])]),  # 重选联动失效

    Slot(name="detail", type=SlotType.TEXT, label="描述具体故障", required=True,
         pattern="故障现象/发生时间/影响范围", max_len=100,
         hooks=[DefaultFill(
             when=lambda rt: rt.card_mode(),                  # 卡片路径（branch=b）
             value=lambda rt: f"{rt.category_short()}，详见现场照片")]),

    Slot(name="photos", type=SlotType.MEDIA, label="现场照片", required=True,
         check_list="repair_category.shots",                  # 按类别拍摄清单（规则校验，0 LLM）
         card="photo_guide"),

    Slot(name="expect_visit", type=SlotType.ENUM, label="期望上门", required=True,
         dict_source="static:expect_visit", card="seg_card"),

    Slot(name="impact_business", type=SlotType.ENUM, label="影响营业", required=True,
         dict_source="static:impact", predict=True,           # extract 预判，点选确认
         card="seg_card"),

    Slot(name="phone", type=SlotType.CONTEXT, label="联系电话", required=True,
         dict_source="user_context", source_field="phone", card="phone_confirm"),  # 默认本人，进议程（确认卡）

    # ---- 选填组：不进议程 ----
    Slot(name="expect_process", type=SlotType.TEXT, label="期望处理",
         required=False, ask=False),
]

# submit 前的包级校验（在引擎通用"必填齐"之后执行）
SUBMIT_CHECK = SubmitCheck(
    rule=lambda rt: None if rt.photo_list_ok() else "现场照片未按拍摄清单传齐")

# 静态字典（不查库的枚举）
STATIC_DICTS = {
    "expect_visit": ["维修人员尽快上门", "可等待安排"],
    "impact": ["是", "否"],
}
