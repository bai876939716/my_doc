# 运维申请 · Redis 容器（AI 填报服务配套）

> 用途：直接复制「一、工单文案」发给运维；被追问看「二、备问」；运维要配置文件时给「三、YAML 备用」。
> 背景：AI 会话存档依赖 RediSearch/JSON 模块（检查点库 langgraph-checkpoint-redis 需建 FT 索引）。**双实测（2026-11-07）**：本机 redis-stack 7.4.7（ReJSON+search 模块全带）检查点读写全通；公司 dev Redis 5.0.5 无模块，`saver.setup()` 第一步即报 `unknown command 'FT.INFO'`（报错原文已归档）——故 agent 服务自带 redis-stack 容器（单实例、仅命名空间内可达、故障半径只在 AI 服务内）。

---

## 一、工单文案（复制这段）

> 需要在 **htm-dev 集群 python 命名空间**（和 AI 填报服务同命名空间）部署一个 Redis 容器：
>
> - **镜像**：`m.daocloud.io/docker.io/redis/redis-stack-server`（国内镜像站，构建节点可拉）——用 stack 版是因为需要 RedisJSON 模块（AI 会话存档依赖，公司现有 5.0.5 实例不满足，已实测确认）
> - **规格**：单实例即可，**不需要**主从/哨兵/集群；内存 limit 1Gi、CPU 0.5 核（试点期实际用量 <100MB）
> - **持久化**：挂载 PVC 数据卷 + 开启 AOF，重启不丢数据
> - **密码**：设访问密码（放 Secret）；**仅命名空间内可达**，不对外、不开公网
> - **命名**：按平台规范，建议 `dev-redis-agent`（Service：`dev-redis-agent-svc`），与 AI 服务 `dev-python-provider-agent-svc` 一套命名
>
> 用途：AI 报修填报服务的会话存档（试点期），负责人：Harvey。

## 二、备问三条

| 问 | 答 |
|---|---|
| 资源要多大？ | 内存 1Gi 封顶（实际 <100MB）、CPU 0.5 核；试点期几十单/天的量 |
| 为什么不用公司的 Redis？ | 版本差一代（公司 5.0.5，需要 8+JSON 模块，已实测确认）；往共享实例写高频会话数据有挤爆存量业务的风险 |
| 谁维护？出问题影响谁？ | 与 AI 服务同一负责人；故障半径只在 AI 服务内（会话降级为卡片/回表单，不碰任何其他系统），K8s 自动拉起 |

## 三、YAML 备用（运维要求提供配置时给；以平台实际规范为准——命名/PVC/Secret 的创建方式平台可能有自己流程）

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dev-redis-agent
  namespace: python
spec:
  replicas: 1
  selector:
    matchLabels: { app: dev-redis-agent }
  template:
    metadata:
      labels: { app: dev-redis-agent }
    spec:
      containers:
        - name: redis
          image: m.daocloud.io/docker.io/redis/redis-stack-server:latest
          args: ["--appendonly", "yes"]
          env:
            - name: REDIS_ARGS
              value: "--requirepass $(REDIS_PASSWORD)"
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef: { name: dev-redis-agent-secret, key: password }
          ports:
            - containerPort: 6379
          resources:
            requests: { memory: 256Mi, cpu: 100m }
            limits: { memory: 1Gi, cpu: 500m }
          volumeMounts:
            - name: data
              mountPath: /data
      volumes:
        - name: data
          persistentVolumeClaim: { claimName: dev-redis-agent-pvc }
---
apiVersion: v1
kind: Service
metadata:
  name: dev-redis-agent-svc
  namespace: python
spec:
  selector: { app: dev-redis-agent }
  ports:
    - port: 6379
      targetPort: 6379
```

需要配套的 PVC / Secret 清单（数据卷 2Gi 起步、密码字符串）也随时可以补——一般平台控制台点两下就有了。
