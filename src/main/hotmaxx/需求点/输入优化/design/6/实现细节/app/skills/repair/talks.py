"""报修技能包 · 话术模板（0 LLM，全部模板化——口径可运营，改文案不走发版）"""

TALKS = {
    "repair.open": (
        "{reporter}你好！直接说**哪儿坏了**就行，比如"
        "「冷柜不制冷了，今天早上开始的，鲜奶都不敢卖了」——"
        "什么现象、什么时候开始、影响多大，说多少算多少，我来帮你归类、填单。"
    ),
    "repair.ask_category_hit": "听下来了。我认出的类别如下——怕报错被驳回，点一下确认（不对就选别的）：",
    "repair.ask_category_miss": "这个我没把握归类，请从下面选一个（拿不准就选「其他」）：",
    "repair.ask_photos": "类别登记好了。麻烦拍两张**现场照片**：{shots_list}，维修师傅好提前判断。",
    "repair.ask_visit": "照片收到。**期望上门**怎么安排？",
    "repair.ask_impact_pre": "这故障**影响做生意吗**？{predict_hint}",
    "repair.ask_impact": "这故障**影响做生意吗**？",
    "repair.ask_phone": "**联系电话**就用你本人的 {phone_masked} 吗？替别人报修可以改。",
    "repair.confirm": "信息齐了，请核对：",
    "repair.updated": "已把{field_label}更新为「{value}」，继续当前的步骤就行。",
    "repair.pull_back": "我只帮你填报修单——说故障、选类别、传照片就行，其他的帮不了啦。",
    "repair.fallback": "AI 手速有点慢，先用卡片点选填，一样能交单。",
    "repair.done": "提交成功 ✅ 报修单号：{order_no}，状态：{status}。",
}
