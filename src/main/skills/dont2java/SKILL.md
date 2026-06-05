---
name: dotnet-to-java-migration
description: Migrate .NET Framework / C# Web API code to Java (Spring Boot). Use when converting C# controllers and services to Java, planning .NET to Java migration, or when the user asks for C# to Java translation, porting, or 迁移.
---

# .NET 代码转 Java 迁移

在用户提出将 .NET/C# 代码转为 Java（如 Spring Boot）时，按本技能执行：先定范围与技术栈，再做架构映射与分阶段迁移，保证接口与数据兼容。

## 1. 先明确范围与目标栈

**范围（三选一或自定义）**

| 范围 | 说明 |
|------|------|
| **最小** | 单接口链路：Controller → AppService → 核心 Service/Manager，能独立跑通一条 API |
| **模块** | 整个功能模块（如某 Controller 下全部接口 + 依赖的 Service/Repository/MQ） |
| **域** | 整个业务域，与现网边界一致 |

**必须向用户确认**

- 目标 Java 技术栈：JDK 版本、Spring Boot 版本、ORM（JPA/MyBatis）、MQ、Redis、配置中心
- 是否与现网 C# **共库、共表、共 MQ**；若共 MQ，消息格式是否必须兼容
- 多公司/分库分表：是否沿用现网设计（如按 co_id 分表、多数据源）
- 用户与权限：Java 侧用户上下文从哪来（如 @InjectUser CurrentUser、JWT、网关）
- 发布策略：直接替换、双写对比、还是先只读

## 2. 架构与分层映射

| C# 现状 | Java 建议 |
|---------|-----------|
| ASP.NET Web API Controller | `@RestController` + `@RequestMapping`，URL 与现网一致 |
| BuildUc / BaseService.CurrentUserContext | 请求入口解析后放入 ThreadLocal 或 Request 作用域；Controller 用 `@InjectUser CurrentUser` 等注入，不在 Controller 内再建 UserContext |
| WithCa(lambda) / OpenDb（事务 + 连接） | `@Transactional(rollbackFor = Exception.class)` 或 `TransactionalHelper.execute(() -> ...)`，事务边界放在 App 层 |
| IocManager / BindCopy(coId) | 多数据源/多租户：按 co_id 切数据源或 Schema，用 `@DynamicDataSource(coId = "#coId")` 或 AbstractRoutingDataSource |
| Repository（手写 SQL + 分表） | MyBatis Mapper + 分表名解析（与 C# GetXxxTableName 逻辑一致），Mapper 方法上加动态数据源注解 |
| EventBus.XXXEvent / 发 MQ | 发 MQ 消息，事件名与 payload 与现网约定一致（共 MQ 时 JSON 字段兼容） |
| 无参/带参构造函数 new Service(uc, ca) | Spring 注入 Service/Manager；需要“当前用户/公司”时从 CurrentUser 或上下文获取，不通过构造函数传 Connection |

**要点**

- 用户上下文：一次解析，下游通过注入的 Provider 或 ThreadLocal 获取，避免在方法签名里层层传递。
- 多公司：C# 的 BindCopy 本质是切换“当前公司上下文”；Java 用路由数据源 + 公司上下文在同一事务内切库/切表。

## 3. Java 命名规范（小驼峰）

**约定**：Java 中属性、方法均使用**小驼峰**（camelCase）。.NET 里若出现**下划线**命名（属性或方法），在 Java 侧要转为小驼峰，不能保留下划线。

| .NET（下划线） | Java（小驼峰） |
|----------------|----------------|
| `as_id`、`outer_as_id`、`drp_co_id_to` | `asId`、`outerAsId`、`drpCoIdTo` |
| 属性 `public long as_id` | 属性 `private long asId`，getter/setter 为 `getAsId()`、`setAsId()` |
| 方法名含下划线 | 方法名改为小驼峰，如 `get_list_with_items` → `getListWithItems` |

**与 C#/JSON 兼容时**：Java 代码里用小驼峰命名，对外协议（JSON、MQ、DB 字段）若需与 .NET 一致，用 `@JsonProperty("as_id")` 等指定序列化名，例如：

```java
private long asId;
@JsonProperty("as_id")
public long getAsId() { return asId; }
@JsonProperty("as_id")
public void setAsId(long asId) { this.asId = asId; }
```

这样既符合 Java 规范，又保证与现网 C# 接口/消息格式兼容。

## 4. 请求/响应与 JSON 兼容

- 请求体：保留与 C# 一致的**序列化字段名**（如 `data`、`asIds`、`confirm`、`isAuto`）；Java 内部用小驼峰，用 `@JsonProperty` 对齐对外字段名。
- 响应体：与 C# 统一（如 `items`、`as_id`、`isSuccess`、`message`），Java 属性小驼峰 + `@JsonProperty` 控制出参。
- 共库共 MQ 时：消息体的字段名、枚举值需与 C# 一致，便于双写或灰度对比。

## 5. 分阶段迁移步骤（按需执行）

**阶段 0 约定**  
确认范围、技术栈、是否共库共 MQ、分支与目录（如 java/ 子目录或独立模块）。

**阶段 1 基础与上下文**  
工程骨架 → 请求/响应 DTO（与 C# 兼容）→ CurrentUser 构建与注入 → 统一返回体与异常处理（如 `Result<T>`、`@ControllerAdvice`）。

**阶段 2 数据访问**  
表与实体/映射 → 分表策略（与 C# 一致）→ Repository/Mapper 接口与实现 → 多公司数据源/`@DynamicDataSource`。

**阶段 3 核心业务**  
AppService（事务边界）→ Service/Manager（新老分支、Pipeline）→ 校验与规则逐条迁移，核心路径先跑通再补边界。

**阶段 4 MQ 与异步**  
消息体定义 → 生产者（与现网事件等价）→ 消费者（调同一 AppService/Service）→ 异步结果处理（MQ 或线程池）。

**阶段 5 配置与开关**  
UConfig/开关用配置中心或 Redis，键与现网一致或做映射表。

**阶段 6 联调与上线**  
单测/集成测试、接口兼容性、文档与差异说明。

## 6. 常见语法与习惯映射

| C# | Java |
|----|------|
| `?.` 空条件 | `Optional` 或 `obj != null ? obj.getX() : null` |
| `??` 空合并 | `Optional.ofNullable(a).orElse(b)` 或三元 |
| `string.IsNullOrEmpty(s)` | `s == null \|\| s.isEmpty()` 或 Apache Commons `StringUtils.isEmpty(s)` |
| LINQ `Where/Select/ToList` | `Stream.filter/map/collect(Collectors.toList())` |
| `List<T>` / `IList<T>` | `List<T>` |
| `Dictionary<K,V>` | `Map<K,V>`（如 `HashMap`） |
| 可选参数 `bool isAuto = false` | 重载方法或 Builder，或保留参数显式传 |
| .NET 下划线属性 `as_id` | Java 小驼峰属性 `asId` + `@JsonProperty("as_id")` 保持 JSON 兼容 |
| `[JsonProperty("as_id")]` | `@JsonProperty("as_id")`（序列化名与 C# 一致，代码内仍用 asId） |
| `[FromBody]` | `@RequestBody` |
| `[Route("SubmitSupplier")]` | `@PostMapping("SubmitSupplier")` |

## 7. 输出与交付建议

- **先出迁移大纲**：范围、技术栈、架构映射表、阶段拆分、待确认问题（见上文“必须向用户确认”），再动代码。
- **代码放置**：按用户指定目录（如项目根目录 `java/`）生成包结构；Controller/Service/Manager/Repository 分层清晰；占位类（如 Result、CurrentUser）注明“若项目已有请替换 import”。
- **README**：在生成目录下写 README，包含与 C# 的对应关系、请求响应示例、占位与待实现清单、构建与接入说明。

## 8. 何时深入参考

- 需要完整阶段清单、表级依赖、MQ/定时任务说明时，可参考项目内 `docs/SubmitSupplier_CSharp_to_Java_迁移大纲.md`。
- 需要具体接口的调用链、依赖表、逻辑图时，可先为 C# 方法整理时序图/活动图（如 PlantUML），再按图逐层转 Java。
