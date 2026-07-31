# 心屿 App 2.0 后端核心服务 — 项目整体介绍

> 仓库：`xinyu-world-simulator-core`  
> 组织：`com.ximayala.soul.haven`  
> 版本：`0.0.1-SNAPSHOT`

---

## 1. 项目概述

**心屿（Soul Haven）App 2.0 后端核心工程** 是一个面向 AI 角色陪伴 / 世界模拟类产品的 **Java 微服务单体仓库（Monorepo）**。用户可在「星球」世界观中与 AI 角色进行多种形态的互动：短对话、群聊、星梦（沉浸式剧情对话）、动态 Feed、记忆系统等。

项目采用 **Maven 多模块** 组织，按业务域拆分为多个可独立部署的 Spring Boot 服务，服务间通过 **Dubbo 3 Triple + Protobuf** 暴露 RPC 契约，App 端统一经由 **BFF 层** 访问 HTTP 接口。

---

## 2. 产品定位与核心能力

心屿 2.0 的核心体验围绕 **AI 角色** 与 **用户身份** 在虚拟世界中的长期互动展开：

| 能力域 | 说明 |
|--------|------|
| **角色系统** | 角色卡、羁绊关系、关系数值、角色相册、多语言 i18n、新手引导卡 |
| **短对话** | 用户与单个 AI 角色的日常聊天，含 LLM 回复、会话总结、星梦邀请触发 |
| **群聊** | 多角色 + 用户的群聊场景，可靠消息投递、Hermes 长链实时推送 |
| **星梦** | 沉浸式剧情对话体验，地图/任务驱动，LLM Agent 编排 |
| **记忆系统** | 跨短对话/群聊/星梦的统一记忆总结、RAG 召回、用户画像 |
| **动态 Feed** | 角色/用户新鲜事、评论回复 Agent、记忆 MQ 联动 |
| **消息 Tab** | 统一会话列表聚合（短对话/群聊/星梦/系统通知） |
| **星球与世界** | 星球坐标、区域、万物志知识库、时间天气、存档（saveId 世界线隔离） |
| **用户系统** | 登录认证（短信/Firebase）、对话身份、新手引导、签到、偏好 |
| **交易与会员** | 贝壳钱包、订阅、Apple IAP、Google Play 支付 |
| **管理后台** | 角色/预设/星球/群聊/短对话/星梦/RAG/模型/任务等运营配置 |
| **自动对话测试** | AI 对话质量自动化评测平台（固定数据集 + AI 生成用户发言） |

---

## 3. 技术架构

### 3.1 技术栈

| 类别 | 技术 |
|------|------|
| 语言 / 运行时 | Java 8 |
| 框架 | Spring Boot 2.7.18、Spring Cloud 2021.0.5 |
| 服务治理 | Spring Cloud Alibaba / Nacos 2021.0.5.0 |
| RPC | Dubbo 3.3.6 Triple + Protobuf 3.25.5 |
| 数据库 | PostgreSQL + MyBatis-Plus 3.5.5 |
| 缓存 | Redis / Redisson 3.27.2 |
| 消息队列 | RocketMQ |
| 长连接 | Hermes |
| API 文档 | SpringDoc OpenAPI 1.7.0 |
| LLM 集成 | DashScope、网宿 LLM、LangChain4j（自动对话服务） |

### 3.2 整体架构图

```text
┌─────────────────────────────────────────────────────────────────┐
│                         客户端 App / 管理后台                      │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  soul-haven-web-service (BFF)    soul-haven-admin-service       │
│  App HTTP 聚合层                  管理后台 REST API               │
└────────────────────────────┬────────────────────────────────────┘
                             │ Dubbo Triple RPC (Protobuf)
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  user │ character │ core │ short-chat │ group-chat │ star-dream  │
│  memory │ inbox │ trade │ common │ auto-conversation            │
└───────┬─────────┬──────┬────────────┬────────────┬──────────────┘
        │         │      │            │            │
        ▼         ▼      ▼            ▼            ▼
   PostgreSQL  Redis  RocketMQ   Hermes 长链   OSS / LLM
        │
        ▼
      Nacos（服务发现 + 配置中心）
```

### 3.3 典型请求链路

```text
App HTTP 请求
  → soul-haven-web-service Controller
  → Dubbo Triple RPC Client
  → 业务域 Service RPC Provider
  → Service / Mapper → PostgreSQL
  → RocketMQ / Hermes / Redis（异步或实时能力）
```

### 3.4 模块组织模式

每个业务域通常成对出现：

- **`*-service-api`**：RPC 契约层，包含 Protobuf IDL（`src/main/proto`）与生成代码
- **`*-service`**：服务实现层，包含 Spring Boot 启动类、Dubbo Provider、Controller、Service、Mapper、Entity、MQ 消费者等

跨服务接口变更的标准流程：**先改 proto 契约 → 更新 Provider → 更新 Consumer（含 BFF）**。

---

## 4. 模块一览

根 `pom.xml` 共包含 **27 个子模块**（13 对 api/service + logging + oss-starter）：

| 模块 | 类型 | 职责 |
|------|------|------|
| `soul-haven-web-service` | 服务 | **App BFF 层**，对客户端暴露 HTTP，聚合调用各后端服务 |
| `soul-haven-admin-service` / `-api` | 服务 + 契约 | **管理后台**，角色/预设/星球/群聊/短对话/星梦/RAG/模型等 |
| `soul-haven-user-service` / `-api` | 服务 + 契约 | 用户、登录认证、身份、偏好、新手引导、签到 |
| `soul-haven-character-service` / `-api` | 服务 + 契约 | 角色卡、羁绊关系、关系数值、角色相册 |
| `soul-haven-core-service` / `-api` | 服务 + 契约 | 星球、动态 Feed、消息 Tab 聚合、Push、OSS、用户版本 |
| `soul-haven-short-chat-service` / `-api` | 服务 + 契约 | 短对话、消息、会话、总结、星梦邀请触发 |
| `soul-haven-group-chat-service` / `-api` | 服务 + 契约 | 群聊、可靠消息、会话总结、Hermes 长链 |
| `soul-haven-star-dream-service` / `-api` | 服务 + 契约 | 星梦业务、星梦会话、LLM 调用、星梦长链 |
| `soul-haven-memory-service` / `-api` | 服务 + 契约 | 记忆总结、RAG、向量库、用户画像 |
| `soul-haven-inbox-service` / `-api` | 服务 + 契约 | 应用内消息、通知、收件箱 |
| `soul-haven-trade-service` / `-api` | 服务 + 契约 | 贝壳钱包、订阅、Apple IAP、Google Play |
| `soul-haven-common-service` / `-api` | 服务 + 契约 | 通用能力：翻译、审核、DashScope、网宿 LLM |
| `soul-haven-auto-conversation-service` / `-api` | 服务 + 契约 | AI 对话质量自动化测试平台 |
| `soul-haven-logging` | 公共库 | 日志公共模块 |
| `soul-haven-oss-spring-boot-starter` | Starter | OSS 对象存储 Spring Boot 自动配置 |

### 可独立启动的服务（13 个）

每个 `*-service` 模块均有 `@SpringBootApplication` 启动类，可独立部署：

| 启动类 | 服务 |
|--------|------|
| `WebServiceApplication` | BFF |
| `AdminServiceApplication` | 管理后台 |
| `UserServiceApplication` | 用户 |
| `CharacterServiceApplication` | 角色 |
| `SoulHavenCoreServiceApplication` | 核心 |
| `ShortChatServiceApplication` | 短对话 |
| `GroupChatServiceApplication` | 群聊 |
| `StarDreamServiceApplication` | 星梦 |
| `SoulHavenMemoryServiceApplication` | 记忆 |
| `SoulHavenInboxServiceApplication` | 收件箱 |
| `SoulHavenTradeServiceApplication` | 交易 |
| `CommonServiceApplication` | 通用 |
| `SoulHavenAutoConversationServiceApplication` | 自动对话 |

---

## 5. 核心业务域详解

### 5.1 短对话（Short Chat）

用户与单个 AI 角色的日常聊天，是心屿最核心的交互形态之一。

- **关键技术**：LLM 提示词组装、Rolling Wait 引擎、消息频控（Redis 滑动窗口）、存档隔离（`saveId` 世界线）
- **关联能力**：会话总结 → 记忆服务、星梦邀请触发、角色关系状态卡、消息 Tab 聚合
- **文档**：`docs/short-chat/`

### 5.2 群聊（Group Chat）

多角色 + 用户参与的群聊场景。

- **关键技术**：可靠消息投递、Hermes 长链推送、群聊总结、成员占用管理
- **文档**：`docs/group-chat/`

### 5.3 星梦（Star Dream）

沉浸式剧情对话体验，支持地图驱动、任务系统、Agent 编排。

- **关键技术**：LLM Agent Run、预设/提示词管理、星梦长链、记忆回调
- **文档**：`docs/admin/星梦相关/`

### 5.4 记忆系统（Memory）

解决 AI 角色「失忆」问题，实现跨场景记忆连贯。

- **覆盖范围**：短对话记忆、星梦记忆（含多人拆分）、群聊记忆（双向融合）、用户画像、约定管理
- **技术目标**：高可用降级、异步总结无真空期、后台动态配置阈值
- **文档**：`soul-haven-memory-service/doc/记忆管理.md`

### 5.5 消息 Tab（统一会话列表）

将会话生命周期事件通过 RocketMQ 写入聚合服务，客户端通过 Hermes 长链 `SessionListReq` 拉取统一列表。

| chatType | 含义 |
|----------|------|
| 0 | 短对话 |
| 1 | 群聊 |
| 2 | 星梦 |
| 3 | 星梦群 |
| 4 | 系统通知 |

- **文档**：`docs/chat/`、`docs/message-tab/`

### 5.6 角色系统（Character）

角色卡完整档案管理，含形象、设定、羁绊关系、关系数值、相册等。

- **核心概念**：源卡、派生卡（i18n 多语言）、原卡绑定、关系数值、公共/个性化内容生产
- **文档**：`docs/admin/角色相关/`、`docs/character/`

### 5.7 动态 Feed

角色/用户的「新鲜事」动态流，含评论回复 Agent、陌生人冷启动、记忆 MQ 联动。

- **文档**：`docs/feed/`

### 5.8 用户系统（User）

登录认证（短信验证码 / Firebase）、对话身份（Identity）、新手引导问卷、签到、偏好设置。

- **文档**：`docs/userprofile/`

### 5.9 交易与会员（Trade）

贝壳虚拟货币钱包、会员订阅、Apple IAP / Google Play 支付回调。

- **文档**：`docs/trade/`

### 5.10 自动对话测试（Auto Conversation）

独立的 AI 对话质量自动化测试平台，不影响生产数据。

- **双模式**：固定数据集驱动 / AI 智能生成用户发言
- **阶段化任务**：9 种演进目标（casual_chat、memory_plant、memory_recall、emotion_warmup 等）
- **四种初始化模式**：新建会话、基于现有会话、快照恢复、模板创建
- **文档**：`docs/auto-conversation/`

---

## 6. 数据与基础设施

### 6.1 数据库

- 主数据库：**PostgreSQL**
- SQL 脚本分散在各模块 `sql/` 目录及 `docs/**/sql/` 中
- 自动对话服务使用独立库：`soul_haven_auto_conversation`

### 6.2 缓存与状态

- **Redis / Redisson**：缓存、限流、分布式锁、延迟任务、Rolling Wait 状态
- 关键设计：Redis Key 均含 `saveId`，实现存档（世界线）隔离

### 6.3 消息队列

RocketMQ 用于跨服务异步事件，典型场景：

- 消息 Tab 会话生命周期事件
- 记忆总结触发与回调
- 动态 Feed 记忆 MQ
- 星梦事件通知
- 订阅事件

### 6.4 长连接

**Hermes** 用于：

- 实时消息推送（短对话/群聊/星梦）
- 统一会话列表增量更新（`session_list_{uid}`）
- 正在输入状态、Rolling Wait 等

### 6.5 服务发现与配置

**Nacos** 提供服务注册发现与配置导入（`spring.config.import`），各环境配置：

```text
<module>/src/main/resources/
  application.yml
  application-dev.yml
  application-fat.yml      ← 默认 profile
  application-prod.yml
```

---

## 7. RPC 契约与 API 设计

### 7.1 Protobuf IDL

RPC 契约位于各 `*-service-api/src/main/proto/` 目录。修改时需关注：

- 字段编号兼容性（不复用已发布字段号）
- BFF 层 HTTP DTO 与 Protobuf 映射
- Provider 实现完整性
- 消费方依赖升级

### 7.2 HTTP 接口层

| 入口 | 面向 | 说明 |
|------|------|------|
| `soul-haven-web-service` | App 客户端 | BFF 聚合，统一鉴权与用户上下文 |
| `soul-haven-admin-service` | 管理后台 | 运营配置、内容管理、测试工具 |

BFF Controller 示例（按域划分）：

- `BffAuthController` / `BffUserController` — 认证与用户
- `ShortChatSessionController` / `ShortChatMessageController` — 短对话
- `GroupChatSessionController` / `GroupChatMessageController` — 群聊
- `StarDreamRootBffController` / `StarDreamTaskBffController` — 星梦
- `AppFeedController` / `AppPlanetController` — Feed 与星球
- `TradeController` — 交易
- `BffInboxMessageController` — 收件箱

### 7.3 统一响应格式

```json
{
  "code": 200,
  "msg": "success",
  "data": { ... }
}
```

---

## 8. 项目目录结构

```text
xinyu-world-simulator-core/
├── pom.xml                          # 根 POM，模块管理与依赖版本
├── README.md                        # 项目说明
│
├── soul-haven-web-service/          # App BFF
├── soul-haven-admin-service/        # 管理后台
├── soul-haven-admin-service-api/
├── soul-haven-user-service/         # 用户
├── soul-haven-user-service-api/
├── soul-haven-character-service/    # 角色
├── soul-haven-character-service-api/
├── soul-haven-core-service/         # 核心（星球/Feed/Push）
├── soul-haven-core-service-api/
├── soul-haven-short-chat-service/   # 短对话
├── soul-haven-short-chat-service-api/
├── soul-haven-group-chat-service/   # 群聊
├── soul-haven-group-chat-service-api/
├── soul-haven-star-dream-service/   # 星梦
├── soul-haven-star-dream-service-api/
├── soul-haven-memory-service/       # 记忆
├── soul-haven-memory-service-api/
├── soul-haven-inbox-service/        # 收件箱
├── soul-haven-inbox-service-api/
├── soul-haven-trade-service/        # 交易
├── soul-haven-trade-service-api/
├── soul-haven-common-service/       # 通用
├── soul-haven-common-service-api/
├── soul-haven-auto-conversation-service/    # 自动对话测试
├── soul-haven-auto-conversation-service-api/
├── soul-haven-logging/              # 日志公共模块
├── soul-haven-oss-spring-boot-starter/      # OSS Starter
│
├── docs/                            # 业务与接口文档（218+ 篇）
│   ├── app/                         # App HTTP 接口
│   ├── admin/                       # 管理后台接口
│   ├── short-chat/                  # 短对话设计
│   ├── group-chat/                  # 群聊设计
│   ├── chat/                        # 会话系统与消息 Tab
│   ├── feed/                        # 动态 Feed
│   ├── topic/                       # 话题库
│   ├── push/                        # Push 推送
│   ├── trade/                       # 交易支付
│   ├── userprofile/                 # 用户系统
│   ├── character/                   # 角色关系
│   ├── auto-conversation/           # 自动对话测试
│   └── message-tab/                 # 应用内消息通知
│
├── openspec/                        # OpenSpec 变更规范与归档
│   └── changes/archive/             # 已归档的变更记录
│
└── exports/                         # 数据导出文件
```

---

## 9. 开发指南

### 9.1 环境要求

- JDK 1.8
- Maven 3.6+
- PostgreSQL、Redis、Nacos、RocketMQ
- Maven 私服访问配置（`~/.m2/settings.xml`）

### 9.2 构建

```bash
# 全量构建
mvn clean install

# 单模块构建（含依赖）
mvn clean install -pl soul-haven-web-service -am
```

### 9.3 启动

```bash
# 以 BFF 为例
mvn spring-boot:run -pl soul-haven-web-service -Dspring-boot.run.profiles=dev
```

### 9.4 变更管理

项目使用 **OpenSpec** 管理功能变更，变更记录位于 `openspec/changes/archive/`，涵盖短对话、群聊、自动对话、角色等域的迭代历史。

---

## 10. 注意事项

- 不要提交 `target/`、IDE 产物、日志和本地配置
- 不要把生产密钥、Nacos 密码、MQ AccessKey 等明文写入仓库
- 跨服务接口优先修改 `*-service-api` 的 proto 契约
- 涉及消息 Tab、短对话、群聊、支付、记忆总结等状态流转时，建议补充单元测试或集成测试
- 存档（`saveId`）是世界线隔离的核心概念，Redis Key、会话、记忆等均与之关联

---

## 11. 快速导航

| 想了解… | 去看… |
|---------|-------|
| 项目基本说明 | `xinyu-world-simulator-core/README.md` |
| 短对话系统 | `docs/short-chat/` |
| 群聊系统 | `docs/group-chat/` |
| 星梦系统 | `docs/admin/星梦相关/` |
| 记忆管理 | `soul-haven-memory-service/doc/记忆管理.md` |
| 用户系统 | `docs/userprofile/心屿2.0-用户系统设计文档.md` |
| 角色系统 | `docs/admin/角色相关/心屿-角色设计文档.md` |
| 消息 Tab | `docs/chat/消息Tab-统一会话列表-聚合服务-MQ消息文档.md` |
| 自动对话测试 | `docs/auto-conversation/README.md` |
| 变更历史 | `openspec/changes/archive/` |

---

*本文档基于 `xinyu-world-simulator-core` 仓库当前代码与文档整理，适用于新人 onboarding 与架构概览。*
