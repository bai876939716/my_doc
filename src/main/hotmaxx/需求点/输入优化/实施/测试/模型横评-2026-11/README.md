# 百炼模型横评（2026-11）

目标：给报修 Agent 长期选型拿数据依据（现役 qwen-plus 为基线），顺带产出百炼主流模型横评报告。
上一次快测见 `../模型对比测试结果.md`（12 条手工对比 3 款），本次升级为 10 模型 × 65 条 × 3 跑的系统性横评。

## 模型清单（models.json）

| 品牌 | 智能款 | 快速款 |
|---|---|---|
| Qwen | qwen4-max | qwen3.8-flash（另有 qwen3.7-plus 均衡款、qwen-plus 现役基线） |
| DeepSeek | deepseek-v4-pro-0813 | deepseek-v4.1-flash-1017 |
| 月之暗面 | kimi-k3.1-pro | kimi-k3 |
| 智谱 | glm-5.3 | ——（百炼未挂 GLM 快速款） |
| MiniMax | MiniMax-M2.5 | ——（⚠️ 官方标注不支持结构化输出参数，JSON 合法率单独看） |

清单依据 2026-11-16 更新的官方模型广场；型号若有下线/改名，改 `models.json` 即可。

## 数据集

**L1 通用层 `dataset_l1.jsonl`（40 条，5 维度 ×8）**
结构化抽取 / 中文指令遵循 / 安全边界（该拒的拒、不该拒的别过度拒）/ 长上下文埋针（8k/16k/32k，针埋正中）/ 推理计算。

**L2 业务仿真层 `dataset_l2.jsonl`（25 条，6 维度）**
骑墙值 / 推断代填 / 非法值与归一 / MustPick 点选 / 挂起与注入防御 / 多槽位抽取与静默吞值。
来源：`../../报修Agent-评测用例集-v1.md` 中纯模型侧任务（B01-B33 抽子集 + C14 的 64 格矩阵抽样），
断言全部规则化，直接回答「换模型行不行」。

## 用法

```bash
cd 本目录
export DASHSCOPE_API_KEY=sk-xxx        # 公共域名 key；生产专属域名只认 Qwen 系，横评统一走公共口

python3 run_eval.py smoke              # 第一步：每模型 2 条冒烟，验证开通与连通
python3 run_eval.py run                # 全量 10×65×3（断点续跑，中断了直接重跑同命令）
python3 run_eval.py grade              # 出 报告-模型横评.md（不调接口，可反复重评）
```

可选：`run --dataset l2`（只跑业务层）/ `--models qwen-plus,kimi-k3` / `--runs 3`。

## 口径（与生产对齐的点）

- **prompt-only，不用 response_format**——生产路径就是纯提示词约束，JSON 合法率本身是被测维度；
- L2 的 system prompt = `prompt_repair_system.txt`，**从 AgentServerCenter 生产代码渲染**（业务段在前+协议壳在后，
  Mock 边界静态字典口径）。生产提示词改动后需重新渲染同步此文件；
- L2 用户消息拼装（流程状态/槽位快照/三引号包裹）与 `llm.py joint_extract` 逐字一致；
- temperature=0；qwen 系带 `enable_thinking=false`（同生产 extra_body），非 Qwen 不带；
- 评分时剥 ``` 围栏后解析（同生产 `_strip_fences`），长上下文维度有 max_len 限制答案长度。

## 已知注意

- 第三方模型走公共 dashscope 域名，若某型号未开通，smoke 会直接点名（4xx 不重试）；
- MiniMax-M2.5 结构化输出无 API 参数支持，其 L2 JSON 合法率最能说明「prompt-only 下限」；
- models.json 的 price_in/price_out（元/千token）填上后 grade 可折算成本，没填只出 token 量；
- 长上下文 32k 两条是 token 大头，全量约 10 模型 ×(2×32k+2×16k+4×8k)×3 跑 ≈ 500 万输入 token 上下，冒烟后再全量。
