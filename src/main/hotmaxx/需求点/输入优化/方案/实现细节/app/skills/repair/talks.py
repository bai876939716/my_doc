"""报修技能包 · 话术模板（应和文案全在这里，改口径不用发版、不经过 LLM）

写这几条要守的规矩：
- 前端是纯文本渲染，** 这类 Markdown 记号会原样显示出来；
- 一句话只说一件事；
- 别在话术里写死当前在问哪个字段——降级可能停在任何待办上。
"""

TALKS = {
    "repair.open": (
        "{reporter}你好！哪儿坏了直接说，比如"
        "「冷柜不制冷了，鲜奶都不敢卖了」，我来帮你填单。"
    ),
    "repair.ask_category_hit": "类别是这个吗？点一下确认 ：",   # 不念猜的类别：卡里首位就是首选，用户看得到
    "repair.ask_category_miss": "这个我没把握归类，从下面选一个吧：",
    "repair.ask_photos": "麻烦拍张现场照片，师傅好提前判断。",   # 拍摄清单只在 photo_guide 卡里出现一次
    "repair.ask_phone": "联系电话就用你本人的 {phone_masked} 吗？帮别人报修就换个号。",
    # 档案没号、或登的是座机时问这条：没有默认号可确认，只能直接要。
    # 措辞别说"没查到你的电话"——座机场景那是假话，档案里有，只是师傅打不通。
    "repair.ask_phone_none": "维修师傅上门前会打你电话——发我一个能接到的手机号（11 位，1 开头）。",
    "repair.ask_detail": "用一句话说说故障情况（什么现象、什么时候开始的），我来整理成报修描述。",
    "repair.echo_filled": "记下了：{filled_list}。",   # 只报名不报值：值在确认单和卡片上看
    "repair.echo_category": "类别按你说的登记为「{value}」——不对就说「改类别」重新选。",
    "repair.confirm": "核对一下没问题就提交：",   # 承认的话归 echo_filled/updated_list，这里只留指令，同轮不双承认
    "repair.ask_update_target": "好，{field_label}改成多少？直接发我，或点确认单上的橙色字段。",
    "repair.ask_update_generic": "要改哪项、改成什么？一句话发我就行。",
    "repair.ask_update_target_collecting": "好，{field_label}改成多少？直接发我就行。",
    "repair.ask_update_generic_collecting": "要改哪项、改成什么？一句话发我就行。",
    "repair.repick_category": "好，重新选一下报修类别：",
    "repair.value_rejected": "「{value}」不在可选范围里——从下面选一个吧",
    "repair.phone_invalid": "「{value}」看着不像有效手机号——麻烦重发一个 1 开头的 11 位号码。",
    "repair.invalidated": "刚才的修改让「{items}」作废了，重新提供一下就行——",
    "repair.stale_hint": "类别换了——刚才的「{items}」如果拍的不是现在这个设备，重新传一下；还对得上就直接继续。",
    "repair.pull_back": "我只能帮你填报修单。",
    # 边界场景各配一条应和：LLM 只判意图，文案在这里，运营能改
    "repair.pull_back_progress": "进度得问维修师傅——我先把你的报修单填完。",
    "repair.pull_back_inject": "指令我看不懂也不执行——咱只管报修，说故障吧。",
    # ---- 引擎流程话术：core 按通用键引用，中文留在这里 ----
    "repair.updated_list": "已更新：{fields}",
    "repair.invalid_fields": "还差：{fields}，补一下就能交了。",
    "repair.order_failed": "单子没递上去，稍等一下再点次提交。",
    "repair.attachments_limit": "照片够了（最多{max}张），继续下一步吧。",   # 键名通用（引擎按附件上限取），文案才是报修口径
    "repair.quota_exhausted": (
        "这轮文字我先听不动了——"
        "直接点下面的选项卡继续，一样能把单填完、交出去。"
    ),
    "repair.fallback_pull": (
        "打字咱俩暂时聊不上——点下面的卡片继续就行，"
        "选类别、传照片、出单，一条不少。"
    ),
    # 停在确认单时的变体：这时"点下面的卡片"指错了路（眼前的卡就是确认单本身），
    # 而且除了换电话，别的字段降级轮真改不了，指路得诚实
    "repair.fallback_pull_confirm": (
        "打字咱俩暂时聊不上——改动点确认单上的橙色字段，换电话直接把新号发我。"
    ),
    "repair.quota_exhausted_confirm": (
        "这轮文字我先听不动了——改动点确认单上的橙色字段，换电话直接把新号发我。"
    ),
    # 不指定下一步问谁：降级可能停在任何待办上，下一张卡由引擎按议程发
    "repair.fallback": "没关系，点卡片一样能把单填完：",
}
