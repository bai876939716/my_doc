# .NET 转 Java 迁移 - 参考

## 项目内可引用文档

- **迁移大纲（含阶段与依赖表）**：`docs/SubmitSupplier_CSharp_to_Java_迁移大纲.md`
- **单接口时序/逻辑图示例**：`S3.WebApi/Areas/AfterSaleApi/Controllers/DrpController_SubmitSupplier_Sequence.md`
- **已生成 Java 示例**：`java/` 目录（SubmitSupplier 最小迁移），含 README 与占位说明

## 依赖分析表模板（可让用户先填）

| 内容 | 说明 |
|------|------|
| 数据库表 | 涉及的表与分表规则 |
| 核心业务 | 订单、售后等域概念 |
| 调用关系 | 谁调谁（Controller → AppService → Service → Manager/Repository） |
| 定时任务 | 是否有 Cron 触发 |
| MQ | 发送/消费的 Topic 与消息体 |
| 外部接口 | 是否调第三方 HTTP |

先对 C# 方法做一轮上述分析，再转 Java 时不易漏依赖。

## 命名规范

- Java：**属性、方法一律小驼峰**（camelCase）。
- .NET 下划线（如 `as_id`、`get_list_with_items`）→ Java 改为 `asId`、`getListWithItems`。
- 与 C#/JSON 兼容：Java 内部小驼峰，对外用 `@JsonProperty("as_id")` 等指定序列化名。

## 技术栈对照速查（本次使用）

| .NET | Java |
|------|------|
| .NET Framework 4.8 | JDK 17 |
| ASP.NET Web API | Spring Boot 2.7+ / 3.x，Spring Web |
| Hxj.Data / SqlConnectionAdapter | MyBatis + 多数据源，Druid |
| 手写 SQL + 分表 | MyBatis + @DynamicDataSource(coId = "#coId") |
| 无 DI 的 new Service(uc, ca) | Spring @Service 注入 + CurrentUser 注入 |
| ExecAsync / 内存队列 | MQ（如 ONSMQ/RocketMQ）或 @Async |
| UConfig / Redis | 配置中心或 Spring Data Redis |
