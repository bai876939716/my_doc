# MCP 工具集成 — 序列图

## 完整调用流程

```mermaid
sequenceDiagram
    autonumber

    participant Agent  as LangGraph Agent
    participant MC     as McpClient（单例）
    participant MSMC   as MultiServerMCPClient
    participant DS     as dream-service<br/>MCP Server（Java）
    participant US     as user-service<br/>MCP Server（Java）
    participant BI     as Java 业务接口
    participant SEM    as asyncio.Semaphore(8)

    rect rgb(220, 235, 255)
        Note over MC,US: 阶段一：启动初始化（load_tools）

        MC  ->>+ MSMC : get_tools()，逐个服务建连
        MSMC->>+ DS   : HTTP 连接，拉取工具列表
        DS -->>- MSMC : [queryDailyPlan, writeProducedDreamsBatch ...]
        MSMC->>+ US   : HTTP 连接，拉取工具列表
        US -->>- MSMC : [characterDetail, characterList ...]
        Note right of MSMC: 其余 4 个服务（profile / short / soul-user / core）同理
        MSMC-->>- MC  : 合并 25+ 个工具对象

        Note over MC: 构建两张表：<br/>tool_map     工具名 → 工具对象<br/>_tool_registry  工具名 → 服务名
    end

    rect rgb(220, 255, 230)
        Note over Agent,BI: 阶段二：运行时调用

        Agent->>+ MC  : _invoke("queryDailyPlan", {userId, saveId})
        MC   ->> MC   : _find_tool() 查 tool_map，定位工具对象及所属服务
        MC   ->>+ SEM : acquire()
        SEM -->>- MC  : 拿到令牌（在飞请求 ≤ 8）
        MC   ->>+ DS  : tool.ainvoke(args)  via streamable_http
        DS   ->>+ BI  : 路由到内部 Java 业务方法
        BI  -->>- DS  : JSON  {code:0, data:{items:[...]}}
        DS  -->>- MC  : LangChain text 包装：<br/>"## Original Response\n{...}"
        MC   ->> MC   : _coerce_mcp_result()<br/>切出 JSON，解析为 dict
        MC   ->> SEM  : release()
        MC  -->>- Agent: List[Dict]  业务数据
    end
```

## 说明

| 步骤 | 关键点 |
|------|-------|
| 1–6 初始化 | 启动时一次性连接全部 MCP Server，构建 tool_map，后续调用不再重新连接 |
| 7 查路由 | `_find_tool()` 先查 tool_map，找不到再按 `_TOOL_ROUTING` 静态表兜底 |
| 8 限流 | Semaphore 控制并发上限，LLM 调用和 MCP 调用共用同一个，防 Serverless 实例 OOM |
| 9 HTTP | transport 为 `streamable_http`，Java MCP Server 暴露标准 MCP 端点 |
| 11 解包 | LangChain 把 Java 返回的 JSON 包成 text 格式，需从 `## Original Response` 后截取再解析 |
| Session 失效 | 遇 `session terminated` 等错误触发 `reload_tools()`，重建全部连接后重试 |
