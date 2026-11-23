# 如何:切换到 Postgres 检查点(会话重启不丢)

**适用场景**:memory 模式只够链路调通(重启丢会话、无归档)。要让会话/草稿跨重启存活、终态会话自动归档到 `agent_session_archive` / `agent_message_archive` 表,按本文切换到 postgres 模式。

**前提**:本机装有 Docker;已按教程跑通过服务。

## 步骤

1. **起 PG(仅中间件)**

   ```bash
   docker compose up -d postgres
   ```

   使用 `postgres:17` 镜像(daoCloud 镜像站),初始用户/库均为 `agent`,数据落在命名卷 `asc-pg-data`,容器随便删、数据不丢(来源:`docker-compose.yml`)。

2. **设置两个环境变量**(写入 `.env`)

   ```bash
   CHECKPOINT_MODE=postgres
   PG_DSN=postgresql://agent:agent@localhost:5432/agent
   ```

   注意:agent 服务本身**放进容器**跑时,DSN 里的主机要改成服务名 `postgres`(见 `docker-compose.yml` 注释);本机裸跑 uvicorn 才用 `localhost`。

3. **重启服务**

   ```bash
   uvicorn app.main:app --port 8000 --reload
   ```

   启动日志应出现 `检查点后端 = postgres(会话/草稿重启不丢;TTL 7 天由清理线程兜底)`。

4. **验证就绪**

   ```bash
   curl localhost:8000/readyz
   ```

   `"ready": true` 即 PG 连通;检查点表、归档表在**首次就绪时**自动建立(`app/main.py` 的 `Readiness.setup`)。若启动时 PG 未连上,服务以降级态运行,`/readyz` 转绿前不接流量。

## 行为变化

- 活跃索引从进程内 dict 换成 PG 表(`PgActiveIndex`),TTL 由后台清理线程按 `ACTIVE_TTL_DAYS` 兜底清理
- submit / abandon 终态自动归档,数据核验与报表查归档表(来源:`app/main.py` lifespan、`app/core/pgstore.py`)
- memory 模式无归档、重启丢会话,这是两者的本质差别,不是配置遗漏

## 常见问题

**切了模式但重启后检查点表报错?** 表是首次 `/readyz` 通过才建的;先确认 `curl localhost:8000/readyz` 已 200。

**多副本部署行不行?** 不行。会话互斥锁(`session_guard`)与建单幂等缓存都是进程内的,多副本会串会话;扩容前先落 PG advisory lock(来源:`app/api/sessions.py` docstring 与 TODO)。
