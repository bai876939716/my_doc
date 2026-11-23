# 如何:运行测试

**适用场景**:本地回归、提交前自检。测试全部不联网:LLM 一律换桩(`tests/conftest.py` monkeypatch `main.build_llm`),不需要真实密钥。

## 步骤

```bash
uv sync                        # 首次或依赖变更后
source .venv/bin/activate      # 或 uv run pytest
pytest -v
```

testpaths 已配在 `pyproject.toml`(`tests/`),直接 `pytest` 即可。

## 用例地图

| 文件 | 覆盖 |
|---|---|
| `tests/test_api.py` | 五接口契约(含错误码、幂等、并发锁) |
| `tests/test_engine.py` | 引擎状态流转(打字轮/点选轮/降级向导) |
| `tests/test_dict_sources.py` | 字典来源(静态/在线真值) |
| `tests/test_infra.py` | 基础设施(PG 存储等) |
| `tests/test_neutrality.py` | 框架中立性:假技能包跑通全链路,守住「core 不耦合业务」的线 |

## 专属标记

```bash
pytest -m real_llm -v
```

`real_llm` 标记的用例**不换桩** `main.build_llm`——专测模型配置校验本身(构造客户端不联网),定义见 `pyproject.toml` `[tool.pytest.ini_options] markers`。

## 常见问题

**报缺少 LLM 配置?** 你可能误用了 `-m real_llm` 全量跑;普通用例全部换桩,不需要 `.env` 里有密钥。

**要测真实建单链路?** 用 `poc/e2e_real_order.py` 脚本(需要明道云测试环境配置),不属于 pytest 范畴。
