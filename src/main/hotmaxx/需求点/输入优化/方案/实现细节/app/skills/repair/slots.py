"""报修技能包 · 7 槽位声明（声明顺序 = 议程问序）"""
from app.core.skill_contract import (
    Slot, SlotType, MustPick, OnChange, DefaultFill, SubmitCheck,
)

SLOTS = [
    # ---- 带出组：不进议程，状态条展示 ----
    Slot(name="store",    type=SlotType.CONTEXT, label="门店名称", required=True, ask=False,
         dict_source="user_context", ),
    Slot(name="reporter", type=SlotType.CONTEXT, label="报修人员", required=False, ask=False,
         dict_source="user_context", max_len=50, llm_writable=True,
         admit=("update:len",)),          # 店长替店员报修要能改名，但只在 update 轮、且要过长度
         # 非必填是拍板的：档案查无退空不伪造，空也放行（单上有手机号能联系），不开口问姓名
    Slot(name="date",     type=SlotType.CONTEXT, label="报修日期", required=True, ask=False,
         default="today"),

    # ---- 采集组：进议程（detail 在前：先听用户说故障，类别卡带着 Top3 猜测再来）----
    Slot(name="detail", type=SlotType.TEXT, label="故障情况", required=True,
         max_len=100,
         ask_on_card_path=False,    # 走卡片路径就不问描述：照片本身就是说明，值由下面 DefaultFill 拼
         aliases=("描述",),   # 本槽 label 长，用户说「改描述」时要能点到
         admit=("update:len", "provide:truncate"),   # 改值要长度合规；超长直接截断收下别来回劝
         ask_talk="repair.ask_detail", llm_writable=True,
         hooks=[DefaultFill(
             when=lambda rt: rt.card_mode(),                  # 只有卡片路径才兜默认描述
             value=lambda rt: f"{rt.short('category')}，详见现场照片")]),

    Slot(name="category", type=SlotType.ENUM, label="报修类别", required=True,
         dict_source="repair_category", card="enum_card",
         literal_fill=True,            # 原话唯一命中字典词才直填；多命中（冷藏柜分保内/过保）或靠推断仍弹卡点选
         literal_talk="echo_category",   # 直填成功后说「登记为」——值是按用户原话落的
         reject_talk="repick_category",  # 打字填类别被拦要重发卡，通用「不在可选范围」在这儿说不通
         aliases=("类别", "分类"),                      # 用户点这个词时指的是本槽
         ask_talk="repair.ask_category_miss",          # 没把握时发全量卡
         ask_talk_top3="repair.ask_category_hit",      # 有候选时只发 Top3
         hooks=[MustPick(),                                  # AI 只提名，用户点选才入库
                # 换类别时照片不清值、只提示（拍的若不是新设备用户自己重传）；
                # 系统拼的默认描述跟着新类别重算，用户原话写的描述保留
                OnChange(invalidates=[], reset_defaults=["detail"], hints=["photos"])]),

    Slot(name="photos", type=SlotType.MEDIA, label="现场照片", required=True,
         card="photo_guide", ask_talk="repair.ask_photos",
         guide_tool="get_category_dict",   # 返回 {"shots": {类别末段: 清单}}
         guide_from="category"),           # 清单按类别现值挑

    Slot(name="phone", type=SlotType.CONTEXT, label="联系电话", required=True,
         dict_source="user_context", source_field="phone", card="phone_confirm",
         gesture="phone",            # 前端专用带值手势 {type:"phone", phone:"138…"}/use_default，由引擎 handoff 认领
         ask_talk="repair.ask_phone", llm_writable=True, detector="cn_mobile",
         display="mobile",           # 掩码只在 redact.DISPLAYS 一份，状态条/确认单/日志不会各写一套
         secret=True,                # 真号不进 Prompt；要脱敏点名就在槽位上声明，core 不认字段名
         reject_talk="phone_invalid",  # 缺省话术会说不相干的「不在可选范围」
         ask_talk_no_default="repair.ask_phone_none",   # 档案没号或登的是座机：没默认号可确认，只能直接要
         fill_from_text=True,          # 正问电话时裸号码可直接 provide 落库（正则 + 原话印证双闸）
         aliases=("电话", "手机", "联系", "号码"),
         admit=("detector", "attest")),    # cn_mobile 全匹配 + 原话印证，update/provide 同闸
]

# submit 前的包级校验，跑在引擎通用校验（必填/字典/长度/识别器/附件）之后
# rule(rt) → (拦下的槽位名, 点名的话术) 或 None；槽位名决定引擎把谁塞回议程。
# 照片没有按类别的张数要求，至少 1 张就行，引导卡上的清单只是建议。
SUBMIT_CHECK = SubmitCheck(
    rule=lambda rt: None if rt.count("photos") >= 1
    else ("photos", "现场照片不能为空，请至少传 1 张"))

# 静态字典（不查库的枚举）；当前技能包全部走在线字典，此表留空备扩展
STATIC_DICTS: dict[str, list[str]] = {}
