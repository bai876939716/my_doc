# 公司 Python 部署与集成惯例（取自 ShopCadAI）

> 定位：**agent-service-center 部署构建的公司对齐基准**。全部内容来自公司已有 Python 项目 ShopCadAI（货架规划 PDF 识别服务）的实地勘察（2026-11-07），不是猜测——它的部署形态就是我们项目要走的样子。
> 原始参考：ShopCadAI 仓库根目录 `Dockerfile`、`deployment/README.md`、`PROJECT_CONTEXT.md`（Java 同事看 deployment/README.md「网关」一节即可）。

---

## 一、公司基建的三条大事实（先校准认知）

| # | 事实 | 对我们的含义 |
|---|---|---|
| 1 | **公司是 K8s**：htm-dev 集群 + ingress-nginx，命名空间按语言分工（`python`=Python 服务、`dev`=Java 业务、`go-dev`=Go 网关） | 不要 ECS 了；要的是在 python 命名空间建工作负载（部署平台控制台操作） |
| 2 | **Python 服务不注册 Nacos**：Java 在自己的 Nacos 配置里写 base-url 指 K8s Service 域名，LB 由 K8s Service 天然完成 | 我们不需要 nacos-sdk-python；把服务跑起来，给 Java 侧一个 Service 地址就完事 |
| 3 | **网关接入 = 公共平台「接口登记」**：common-center 门户按 method+path 逐个登记（ShopCadAI 登记 17 项指向 Java 服务），有审批周期 | Java 层暴露给 App 的接口要逐个登记——**新增依赖项，要问审批周期** |

另两则情报：dev 统一网关是 **Go 网关**（`dev-go-gateway-api-svc:80`），与 Spring Cloud Gateway 并存；git 托管在阿里云云效 **Codeup**。

### 1.1 Java 调 Python 的真实接法（照抄这段）

ShopCadAI 的 Java 侧（provider-biz-shop）配置：

```yaml
shop:
  layout:
    recognizer:
      base-url: http://dev-python-provider-shop-cad-ai-svc.python:80
```

- `python` = Service 所在命名空间，`80` = Service 端口（容器端口另选，ShopCadAI 用 18771，我们用 8000）；
- Java 侧程序里一个 HTTP 客户端调这个 base-url，自行拼接具体路径；
- **Python 全程内网，不经网关、不对外**——鉴权、数据权限全在网关和 Java 层完成。

我们项目的对应：Java 层服务配 `agent.base-url: http://dev-python-provider-agent-svc.python:80`（名字以平台规范为准）。

---

## 二、部署拓扑对照

链路总图：[diagrams/01-部署链路对接图.puml](diagrams/01-部署链路对接图.puml)（①打开H5 ②对话API主链 ③模型 ④建单/字典 ⑤手机号 ⑥照片 ⑦存档；⑧记录查询走既有接口不在图中）

```
ShopCadAI 实际形态（= 我们的目标形态）：

店员/运营浏览器
   │ HTTPS（Ingress 域名，如 hotmaxx-shop-cad-ai-dev.hotmaxx.cn）
   ├─ 静态页面 / ──────────► Python 容器（web/ 随 Python 一个镜像发布）
   └─ /shop/layout ──► Ingress ──► Go 网关 ──► Java biz-shop ──► Python 内网 Service
                                                            （base-url 配置指向）

agent 项目落法：
App(H5 对话页)
   │ H5 静态页可由 agent 容器顺带服务（同款 Ingress 域名）
   └─ /agent/** ──► 网关(接口登记过) ──► Java 层(转发代码) ──► agent 内网 Service
                                                            └─► Redis / MySQL / 明道云 / LLM
```

**SSE 与照片上传的现成注解先例**（ShopCadAI 的 Ingress，直接抄）：

```yaml
nginx.ingress.kubernetes.io/proxy-read-timeout: "150"   # ≥ 我们 SSE 要求的 120s
nginx.ingress.kubernetes.io/proxy-body-size: "50m"      # 覆盖照片 multipart 上传
```

---

## 三、Dockerfile 七件套（公司现实约束的产物，逐条抄）

| # | 做法 | 原因 |
|---|---|---|
| 1 | 基础镜像走 `m.daocloud.io` 镜像站 + **固定 digest** | 构建节点连不上 Docker Hub；digest 构建时与官方清单核对后钉死 |
| 2 | Debian 源、PyPI 源换**阿里云镜像** | 国内网络现实 |
| 3 | `USER 10001:10001` 非 root | 部署平台安全要求 |
| 4 | `HEALTHCHECK` 指向健康接口 | 平台按它判定健康（我们用 `/healthz`） |
| 5 | 构建期自检（import + 技能包 load()） | 镜像打完先验证能启动，坏镜像出不了构建 |
| 6 | `.dockerignore` 白名单式（`**` 全排除再逐个放行） | 保证镜像不带数据/Git/日志，比黑名单可靠 |
| 7 | 依赖文件先拷、代码后拷 | 分层缓存：改代码不重装依赖 |

### 3.1 agent-service-center Dockerfile 草稿（POC 时验证）

```dockerfile
# ① 构建节点连不上 Docker Hub：DaoCloud 镜像站；digest 构建时核对官方清单后固定
ARG PYTHON_BASE_IMAGE=m.daocloud.io/docker.io/library/python:3.13-slim-bookworm
FROM ${PYTHON_BASE_IMAGE}

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_DEFAULT_INDEX=https://mirrors.aliyun.com/pypi/simple/

WORKDIR /app

# ② Debian 换阿里源（需要 apt 包时用，如 tzdata）
ARG DEBIAN_MIRROR=https://mirrors.aliyun.com/debian
RUN sed -i "s|http://deb\.debian\.org/debian|${DEBIAN_MIRROR}|g" \
        /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*

# ③ uv 从 PyPI 装（ghcr.io 在构建节点不可达，不 COPY --from）
RUN python -m pip install --no-cache-dir uv

# ④ 依赖层先行（uv 锁定版本，等价公司 requirements.txt 固定版本的精神）
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

# ⑤ 代码层 + 构建期自检（导入主模块；技能包 load() 启动即炸在这里暴露）
COPY app/ ./app/
RUN .venv/bin/python -c "import app.main"

USER 10001:10001
EXPOSE 8000

# ⑥ 健康检查（对齐平台惯例）
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD [".venv/bin/python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3).read()"]

# ⑦ 启动（0.0.0.0 必须写；状态全在 Redis/MySQL，无本地数据目录）
CMD [".venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

---

## 四、命名、端口与环境规范

| 项 | ShopCadAI 实际 | agent 项目采用 |
|---|---|---|
| 服务命名 | `dev-python-provider-shop-cad-ai-svc`（命名空间 `python`） | `dev-python-provider-agent-svc`（**最终以平台命名规范为准**） |
| 端口 | 容器端口自选（18771），Service 端口统一 80 | 容器 8000，Service 80 |
| 环境 | `ACTIVE_ENV=dev/prod` 切换环境默认值 | 同款；LLM/Redis/MySQL 地址随环境变量注入 |
| 分支 | Codeup：`master`=线上 / `dev`=开发 / `merge`=预发布 | agent-service-center 同款三分支 |
| 镜像 | 依赖锁定固定版本 | uv.lock 锁定（不迁回 requirements.txt） |
| 仓库形态 | 仓库带 `deployment/` 说明目录（合包说明、Nginx/Ingress 示例、接口登记清单） | 同款：agent 仓库建 `deployment/` 放部署说明与 Ingress 示例 |

---

## 五、不抄的部分

| 不抄 | 原因 |
|---|---|
| 标准库 http.server 手写路由 | 历史包袱；我们用 FastAPI + async（SSE 需要） |
| requirements.txt | 保留 uv——锁文件更严格，配 `UV_DEFAULT_INDEX` 阿里源即可，与公司「固定版本」精神一致 |
| PDF 识别相关依赖/字体 | 业务无关 |

## 六、待确认清单（拿去问，都是一句话问题）

| # | 问谁 | 问题 |
|---|---|---|
| 1 | 运维/平台 | htm-dev 集群 python 命名空间建工作负载的流程（部署平台控制台怎么操作） |
| 2 | 运维/平台 | Python 项目的构建流水线在哪触发（云效 Flow？）、构建缓存怎么配 |
| 3 | 运维 | Redis 用公司实例还是 python 命名空间自建容器（自建则要 redis-stack 镜像） |
| 4 | 网关负责人 | Java 层新接口在 common-center 登记的审批周期（卡 11.19 联调） |
| 5 | 平台组 | 服务命名规范确认（`-svc` 后缀、命名空间归属） |
| 6 | Java 同事 | 转发代码（对标 biz-shop 的 recognizer base-url 模式）谁写、何时好 |

## 七、对依赖清单的修订（相对 v92 现实版计划）

- **划掉**：ECS 一台 2C4G（改 K8s 工作负载）；
- **划掉**：出公网白名单改确认口径——LLM API 调用从集群内出去的通路怎么开（原 ECS 白名单口径不适用，问运维集群出公网策略）；
- **新增**：接口登记审批（Java 层暴露接口逐个登记）；
- **简化**：Nacos 注册/负载均衡事项全部划掉（K8s Service 天然解决）。
