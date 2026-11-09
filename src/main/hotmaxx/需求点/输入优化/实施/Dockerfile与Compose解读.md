# Dockerfile 与 docker-compose 解读（教学版）

> 定位：agent-service-center 两份部署文件的逐行精讲，写给第一次接触 Docker 的 Java 工程师。
> 配套文件：仓库根目录的 `Dockerfile`、`docker-compose.yml`、`.dockerignore`。
> 结论速记：**镜像管"装什么"，容器管"跑起来"，卷管"数据别丢"；一容器一进程。**

---

## 一、三个地基概念

| 概念 | 是什么 | 类比 |
|---|---|---|
| **镜像 image** | 只读安装包：系统底 + 运行时 + 依赖 + 代码，一层层叠出来 | 装好系统的系统盘镜像 |
| **容器 container** | 镜像跑起来的**进程**，互相隔离，删掉即消失 | `java -jar` 起的那个进程 |
| **卷 volume** | 容器**外**的持久化磁盘，挂进容器用；容器删一百次数据不动 | 外接硬盘 |

容器哲学 = 随时可杀可换（发版就是删旧起新）——所以**数据绝不放容器内**，放卷里（我们的会话数据在 Redis 的卷里，这正是"发版不丢草稿"的物理基础）。

## 二、Dockerfile 逐行解读

Dockerfile = 镜像的构建配方（给运维的装机 SOP，机器执行版）。**执行模型：每条指令 = 一层，层只增不减、逐层缓存**——下次构建从头比对，没变的层直接复用。

| 指令 | 干什么 | 关键点 |
|---|---|---|
| `FROM m.daocloud.io/.../python:3.13-slim-bookworm` | 底镜像：Debian bookworm 精简 Linux + 装好的 Python 3.13 | daocloud 前缀=国内镜像站代理（Docker Hub 直连超时，公司同款解法）；TODO：dev 部署前钉 digest |
| `ENV PYTHONUNBUFFERED=1 ...` | 运行期环境变量 | UNBUFFERED=日志实时出；DONTWRITEBYTECODE=不生成 .pyc；TZ=时区（见专题）；UV_PYTHON_PREFERENCE=uv 用容器自带 Python 不联网下载 |
| `WORKDIR /app` | 设定后续指令工作目录（自动建） | ≈ mkdir + cd |
| `ARG DEBIAN_MIRROR=...` + `RUN sed ... apt ...` | 装 tzdata 的三步仪式（见下节） | ARG=构建期参数（不进最终镜像）；ENV=运行期变量 |
| `RUN pip install ... uv` | 装 uv 本身 | uv 后续装依赖用哪个源由 pyproject 的 `[[tool.uv.index]]` 决定——配置随仓库走 |
| `COPY pyproject.toml uv.lock .python-version ./` + `RUN uv sync --frozen --no-dev` | **依赖层**：只拷清单先装依赖 | `--frozen` 严格按 lock；`--no-dev` pytest 不进生产 |
| `COPY app/ ./app/` | **代码层**：源码进来，与 .venv 会师于 /app | 放依赖层之后——改代码只重建这层（几十秒） |
| `RUN .venv/bin/python -c "from app.main import app; ...; repair.load(); ..."` | **构建期体检** | import 链全量装载检查 + 技能包装配自检；坏镜像出不了构建（fail-fast 推到最早：构建炸＜启动炸＜运行炸） |
| `USER 10001:10001` | 之后的一切以 UID 10001 运行 | 见专题：最小权限 |
| `EXPOSE 8000` | 文档性声明端口 | 真正开端口靠 compose 的 ports |
| `HEALTHCHECK ...` | 容器每 30s 自查 /healthz，3 败标 unhealthy | start-period=20s 宽限期；平台据此探活/重启 |
| `CMD [".venv/bin/uvicorn", ...]` | 起跑线：起容器时执行什么 | JSON 数组写法=信号直达（优雅停机）；0.0.0.0 必须显式 |

### 2.1 专题：`apt-get` 三部曲那行（最常被问）

```dockerfile
RUN sed -i "s|deb.debian.org|阿里源|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*
```

目的：装 tzdata（时区数据库）。要经过"换商店地址→刷商品目录→装货→扔包装盒"：

- `sed -i "s|A|B|g" 文件`：文本替换改源地址（-i 直接改文件；g=一行多个全换）；
- `apt-get update`：**不是升级**，是下载最新"商品目录"（哪些包/什么版本/哪下）；`apt` = Debian 的包管理器，brew 的亲戚；
- `apt-get install -y tzdata`：`-y`=交互自动答 yes（构建时没人敲回车，不加会永远卡住）；`--no-install-recommends`=赠品不要，镜像更小；
- `rm -rf /var/lib/apt/lists/*`：删目录缓存（-r 递归 / -f 强制），给镜像减肥；
- **为什么挤在一个 RUN**：层只增不减——拆三行的话，缓存目录会被固化进中间层，后面删了体积也减不掉；同一层内"下载→用→删"，这层最终只剩 tzdata。**"同层内清理"是 Dockerfile 减肥第一铁律**。

### 2.2 专题：USER 10001——UID 是 Linux 的概念，不是 Docker 的

- Linux 每个**进程**都以某用户（=一个数字 UID）身份运行：root=0，系统服务 1~999，真人 1000+；
- Docker 默认：不写 USER → 容器进程以 **root** 跑；被攻破时攻击者拿到容器内超级用户；
- `USER 10001:10001` = 之后（含 CMD 起的服务）以"编造的普通路人"运行：装不了软件、动不了别人文件——**平时没感觉，被攻破那天是止损闸**（最小权限原则，同公司 Java 服务用 appuser 不用 root 跑）；
- 10001 不需要"创建"：内核只认数字；ShopCadAI 多写了 chown 是因为它要写磁盘文件，我们无状态（数据在 Redis、日志走标准输出）所以省掉；
- 位置讲究：**先以 root 置办家当（装包装依赖）→ 切平民 → 跑服务**。

### 2.3 专题：TZ 为什么设、ShopCadAI 为什么不设

不设 = 容器内 UTC。ShopCadAI 不设是因为它的业务**不在乎**（纯识别工具，不打业务时间字段）。我们必须在乎：`updated_at`、报修日期默认"今天"、埋点 `created_at` 都是服务端 `datetime.now()` 产的——TZ 错 = **写库时间差 8 小时、"今天"可能变"昨天"**，是数据正确性问题。两条路：全链路统一上海时区（我们选的，简单）vs 存储 UTC 展示层转（大厂惯例，心智负担大）。ENV TZ 管心态，tzdata 管真跑（slim 镜像默认不带）。

## 三、docker-compose.yml 逐块解读

compose = **多容器环境的说明书**（本地/开发期工具；生产用 K8s 的同类图纸）。**services 下写几个服务 = 几个独立容器，一服务一容器**：

```
你的 Mac
├── 📦 容器 asc-redis   ← services.redis（买成品镜像）
│     └─ 进程 redis-server；数据 → 卷 redisdata
├── 📦 容器 asc-agent   ← services.agent（build:. 现场构建）
│     └─ 进程 uvicorn
└── （compose 自动建内部网络 + DNS：agent 写 redis://redis:6379，
      "redis" 就是服务名 → 自动解析成 redis 容器 IP）
```

| 配置 | 含义 | 要点 |
|---|---|---|
| `image:` vs `build: .` | 买成品 vs 现场做 | redis 用官方镜像；agent 用我们的 Dockerfile |
| `ports: ["8000:8000"]` | 端口映射 **宿主机:容器** | 外部（Apifox/curl）从 Mac 的 8000 进 |
| `environment: REDIS_URL:` | 环境注入**覆盖**默认值 | 同一个配置键：本机裸跑=localhost、容器内=服务名——代码零改动换世界（≈ dev/prod profile） |
| `depends_on: [redis]` | 起动顺序 | 只保"先起"不保"就绪"——缝隙由 agent 的"连不上当场炸 + restart 自动拉起"兜住 |
| `restart: unless-stopped` | 崩溃自动拉起 | 人为停的不拉 |
| `volumes: redisdata:/data` | 命名卷挂进 Redis 落盘目录 | 容器删了数据在（重启演示的底气） |

**为什么坚持一容器一进程**：生命周期独立（发版重启 agent 不动 Redis）、故障隔离、日志独立——"重启 agent 数据会丢吗"的答案"不会"，前提就是它俩分开。

## 四、这三份文件的"来源与必要性"分层（诚实账）

| 成分 | 来源 | 必要性 |
|---|---|---|
| daocloud 底镜像、换阿里源、USER 10001、HEALTHCHECK、.dockerignore 白名单、构建期自检 | 照抄 ShopCadAI（公司实测惯例） | 硬约束（不换源构建不出/平台要求） |
| 依赖层先行、同层清理 | 通用最佳实践（ShopCadAI 同款） | 效率问题：反着写能跑但每次全量重装 |
| TZ + tzdata | 自判断（ShopCadAI 没设） | 可挑战：业务时间正确性需要；若走全链路 UTC 可删 |

## 五、常用命令速查（含参数解释）

| 命令 | 参数拆解 |
|---|---|
| `docker compose build agent` | 按 Dockerfile 构建镜像；只构建 agent 这一个服务；首次慢（拉底镜像+装依赖），改代码后只重建尾部层 |
| `docker compose up -d` | 起全部服务；`-d` 后台运行（detached），终端还给你 |
| `docker ps` | 列出运行中的容器——**两行=两个容器**；STATUS 里 `(healthy)` 是 HEALTHCHECK 的成果（约 30s 后出现） |
| `curl -s localhost:8000/healthz` | `-s` 静默不显进度；验证容器版服务应答 |
| `docker compose restart agent` | 杀容器重起（≈发版）；数据在卷里不丢——重进 is_resumed=true |
| `docker logs -f asc-agent` | 跟看日志（-f 持续刷新） |
| `docker exec -it asc-agent sh` | 进容器排查（-i 交互 -t 分配终端）；slim 镜像没 bash 用 sh |
| `docker compose down` | 停并删容器（卷保留）；再加 `-v` 才连卷删——慎用 |

## 六、与生产的衔接（一句话版）

本地 compose = 单机版编排图纸；生产 K8s = 同思想的集群版（agent 工作负载 + redis 工作负载 + Service DNS + 平台探活）。镜像一模一样，变的只是"谁来跑它"。实施细节见同目录《公司部署惯例-取自ShopCadAI.md》《运维申请-Redis容器.md》。
