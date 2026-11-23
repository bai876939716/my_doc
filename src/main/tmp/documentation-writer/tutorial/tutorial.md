# 教程:从零跑通一次报修会话

本教程带你在本机把 AgentServerCenter 跑起来,并用 curl 走完一次「建会话 → 发消息 → 提交出单」的完整流程。完成后你将拥有:一个本地运行的服务实例、一次完整的报修对话体验,以及对五个 HTTP 接口的第一手体感。

预计耗时 15~20 分钟。不需要事先了解 LangGraph 或明道云。

## 0. 前置准备

- Python 3.12(`.python-version` 声明,uv 会自动管理,与你系统 Python 版本无关)
- [uv](https://docs.astral.sh/uv/) 包管理器
- 一个百炼(OpenAI 兼容端点)的 `BASE_URL` / `API_KEY` / 模型名 —— 服务必须真实调用 LLM,缺了会拒绝启动

> 本教程为最短路径:用 memory 检查点(进程内,零外部依赖)。Postgres 方式见操作指南《切换到 Postgres 检查点》。

## 1. 安装依赖

```bash
cd AgentServerCenter
uv sync                       # 读 uv.lock 还原依赖,约 30 秒
source .venv/bin/activate     # 或直接用 uv run 代劳
```

## 2. 配置模型

在仓库根建 `.env` 文件(配置优先级:环境变量 > Nacos > .env > 代码默认值,见 `app/config.py` 文件头):

```bash
cat > .env <<'EOF'
LLM_BASE_URL=<你的百炼 compatible-mode 地址>
LLM_API_KEY=<你的密钥>
LLM_MODEL=<模型名>
REAL_ORDER_ENABLED=false
EOF
```

说明:`REAL_ORDER_ENABLED=false` 表示返回 `MOCK-` 开头的单号、字典走静态值,不真写明道云——本教程用不到明道云凭据,先把流程跑通。

## 3. 启动服务并自检

```bash
uvicorn app.main:app --port 8000 --reload
```

另开一个终端验证:

```bash
curl localhost:8000/healthz
# 期望:{"status":"ok","skills":["repair"]}
curl localhost:8000/readyz
# 期望:"ready": true(memory 模式无外部依赖,恒就绪)
```

启动日志应出现 `模型=<模型名> 真实建单=False`。如果启动即报「缺少模型配置」,回到第 2 步——服务拒绝带病启动是刻意行为,不会静默降级(`app/main.py` 的 `build_llm`)。

## 4. 建会话

```bash
curl -X POST localhost:8000/agent/repair/v1/sessions \
  -H 'Content-Type: application/json' -H 'X-Uid: t001' \
  -d '{"store_no": "S001"}'
```

记住返回里的 `session_id`(下文用 `$SID` 指代)。`X-Uid` 头必填——不带会得到 401 与码 `40101`;它是网关注入的身份,服务端只验有无、不做鉴权。

## 5. 对话几轮

用自然语言描述故障(打字轮走 LLM 识别):

```bash
curl -X POST localhost:8000/agent/repair/v1/sessions/$SID/messages \
  -H 'Content-Type: application/json' -H 'X-Uid: t001' \
  -d '{"type": "text", "content": "冷柜不制冷了,今天上午开始的"}'
```

返回的 `messages` 是本轮新增消息,`state` 字段告诉你所处阶段(`collecting` → `confirm` → `done`)。点选轮会下发枚举卡片,回传格式:

```bash
curl -X POST localhost:8000/agent/repair/v1/sessions/$SID/messages \
  -H 'Content-Type: application/json' -H 'X-Uid: t001' \
  -d '{"type": "enum_pick", "field": "category", "value": "<选项原文>"}'
```

手机号轮:

```bash
curl -X POST localhost:8000/agent/repair/v1/sessions/$SID/messages \
  -H 'Content-Type: application/json' -H 'X-Uid: t001' \
  -d '{"type": "phone", "use_default": true}'
```

随时可以查看全量进度(含已收集字段、消息重放):

```bash
curl localhost:8000/agent/repair/v1/sessions/$SID -H 'X-Uid: t001'
```

## 6. 提交出单

当 `state` 变为 `confirm`:

```bash
curl -X POST localhost:8000/agent/repair/v1/sessions/$SID/submit \
  -H 'X-Uid: t001'
```

期望返回 `{"order_no": "MOCK-xxxx", "status": "..."}`。再发一次同样的请求,返回同一单号——submit 是幂等的。

## 7. 收尾

恭喜,你已经走完整个报修流程。可选的退出方式:

```bash
curl -X POST localhost:8000/agent/repair/v1/sessions/$SID/exit \
  -H 'Content-Type: application/json' -H 'X-Uid: t001' \
  -d '{"mode": "abandon"}'
```

## 下一步

- 想让会话重启不丢 → 操作指南《[切换到 Postgres 检查点](../how-to/switch-postgres-checkpoint.md)》
- 想要浏览器界面 → 操作指南《[运行本地演示页](../how-to/run-local-demo.md)》
- 想知道为什么必须带 `X-Uid`、为什么 LLM 没有开关 → 解释《[X-Uid 信任边界](../explanation/identity-trust.md)》
