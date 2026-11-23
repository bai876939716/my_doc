# 如何:运行本地演示页(浏览器可视化联调)

**适用场景**:想在浏览器里直接体验/联调报修对话,而不是手敲 curl。

**关键点**:生产入口 `app.main:app` **没有 CORS 中间件**(2026-11-18 移除,只经网关同源暴露)。演示页要跨源直连本地服务,必须起带 CORS 的演示入口,不要把宽 CORS 加回生产链路。

## 步骤

1. **起演示入口**(业务代码与生产完全一致,只多一层 CORS):

   ```bash
   uvicorn poc.local_demo_app:app --port 8000 --reload
   ```

2. **开匿名访问**(演示页没有登录态,不会带 `X-Uid`):

   ```bash
   export ALLOW_ANONYMOUS=true
   ```

   缺 `X-Uid` 时五接口一律 401/`40101`(服务端强制);只有显式开这个开关才退回 `anonymous`,**生产禁止开启**(来源:`app/api/routes.py` `_uid`)。

3. **浏览器打开** `poc/门店报修-AI填报-演示版.html`,照页面操作即可。

## 注意

- 不要双击 HTML 用 `file://` 直连 8000 端口:预检 `OPTIONS` 会吃 405(无 CORS 的生产入口);这就是演示入口存在的意义。
- 要零 token、确定性的演示(LLM 换桩):改起 `uvicorn poc.mock_llm_app:app --port 8000 --reload`,其余不动。
- `ALLOW_ANONYMOUS=true` 时所有匿名用户共用同一份草稿(都算 `anonymous`),只适合单人演示,不适合多人试用。

## 相关

- 零依赖跑真模型见教程《从零跑通一次报修会话》
- 为什么去掉 CORS 见解释《LLM 无开关与单副本约束》中相关讨论及 `app/main.py` 162-164 行注释
