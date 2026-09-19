# 数据结构设计与完整实战案例（面试专题）

本篇补充前面几篇文档里没有展开的部分：**Redis 具体用什么数据结构、key 怎么设计、Lua 脚本长什么样、MySQL 表结构怎么建、以及一个带真实数字的完整案例**。建议配合 `README.md`、`06-redis-mysql-consistency.md` 一起看。

---

## 一、Redis 数据结构设计（每个 key 具体用什么类型、怎么设计）

| 用途 | Key 设计 | 数据类型 | 说明 |
|---|---|---|---|
| 商品库存 | `seckill:stock:{activityId}:{goodsId}` | String（整数） | 用 `DECR`/Lua 脚本原子扣减，活动开始前用 `SET` 预热为总库存 |
| 用户限购标记 | `seckill:limit:{activityId}:{goodsId}:{userId}` | String | 存在即代表已购买，用 `SETEX` 写入并设置和活动同期的过期时间，活动结束后自动清理，不用手动删 |
| 幂等提交令牌 | `seckill:token:{activityId}:{userId}` | String | 进入页面时下发，值是一个 UUID，`EX` 设置几分钟有效期，提交订单时校验并立即删除 |
| MQ 消费幂等去重 | `seckill:mq:consumed:{messageId}` | String（或用 MySQL 唯一索引表，见下文） | `SETNX` 写入代表首次消费，命中已存在则丢弃这条消息 |
| 库存回滚幂等记录 | `seckill:rollback:{activityId}:{goodsId}` | Set | `SADD` 订单号，返回 0 说明这个订单已经回滚过，直接跳过，防止重复加库存 |
| 大促期间限购标记（省内存版） | `seckill:limit:bitmap:{activityId}:{goodsId}` | Bitmap（`SETBIT`） | 如果用户量特别大（千万级），String 型 key 数量太多占内存，可以把 userId 映射成 bit 位置，一个 key 用位图存所有人的购买状态，内存占用是 String 方案的几十分之一，代价是需要提前做好 userId 到 bit offset 的映射（比如取 userId 的哈希值） |

**为什么库存用 String 而不是别的类型**：秒杀库存只需要一个原子递减的整数计数器，String 的 `DECR`/`INCR` 本身就是原子操作，没有必要用更复杂的结构。

**为什么限购标记用 String + 过期时间，而不是永久保存**：秒杀活动一般是限时的（比如某个活动限购规则只在这一场活动内生效），设置和活动同步的 TTL 可以让 Redis 自动回收这些 key，不需要额外写清理任务；如果限购规则是"永久限购一次"（比如某个商品终身限购），才需要去掉 TTL 或者用数据库唯一索引长期兜底。

**为什么回滚记录用 Set 而不是 String**：一个商品在一场活动里可能有多笔订单需要回滚，Set 天然适合存"一批已处理过的订单号"，`SADD` 的返回值（1 表示新增成功、0 表示已存在）刚好可以当作幂等判断的返回信号，不需要先 `SISMEMBER` 判断再 `SADD`，一步到位。

---

## 二、核心 Lua 脚本（可以直接在面试白板上默写的版本）

### 2.1 库存扣减 + 用户限购标记（一次脚本完成两件事）

```lua
-- KEYS[1] = 库存key，例如 seckill:stock:9001:3001
-- KEYS[2] = 限购key，例如 seckill:limit:9001:3001:1001
-- ARGV[1] = 限购key的过期时间(秒)

-- 第一步：先判断该用户是否已经购买过，命中就直接拒绝，不再消耗后面的库存判断
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -2
end

-- 第二步：判断库存是否充足
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil or stock <= 0 then
    return -1
end

-- 第三步：扣减库存
redis.call('DECR', KEYS[1])

-- 第四步：标记该用户已购买，并设置过期时间
redis.call('SETEX', KEYS[2], ARGV[1], 1)

return 1
```

返回值约定：`1` 抢购成功，`-1` 库存不足，`-2` 用户已购买过。因为整段逻辑在 Redis 里是单线程串行执行的，不会出现"检查通过了但还没扣减，另一个请求又检查通过"的竞态窗口。

### 2.2 幂等提交令牌校验（校验和删除必须是一个原子操作）

```lua
-- KEYS[1] = token key，例如 seckill:token:9001:1001
-- ARGV[1] = 前端提交的token值

local storedToken = redis.call('GET', KEYS[1])
if not storedToken or storedToken ~= ARGV[1] then
    return 0
end

redis.call('DEL', KEYS[1])
return 1
```

**这里的关键点**：如果拆成两条命令（先 `GET` 校验，校验通过后再单独 `DEL`），中间有个时间差，并发的重复提交请求可能都读到 token 还没被删，都校验通过——所以必须写成一个 Lua 脚本保证"校验+删除"原子执行。

---

## 三、MySQL 表结构（真实 DDL）

```sql
-- 库存表：version 用于乐观锁，stock字段配合条件更新语句即可完成原子扣减，version不是必需但方便追踪并发冲突次数
CREATE TABLE seckill_stock (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id  BIGINT NOT NULL,
    goods_id     BIGINT NOT NULL,
    total_stock  INT NOT NULL COMMENT '活动总库存,用于对账',
    stock        INT NOT NULL COMMENT '剩余库存',
    version      INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_activity_goods (activity_id, goods_id)
);

-- 订单表：核心兜底是 uk_user_activity_goods 这个唯一索引，防止超买穿透到最后一步
CREATE TABLE seckill_order (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no      VARCHAR(32)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    activity_id   BIGINT       NOT NULL,
    goods_id      BIGINT       NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消 3已完成 4退款中 5已退款',
    amount        DECIMAL(10,2) NOT NULL,
    create_time   DATETIME     NOT NULL,
    pay_time      DATETIME     NULL,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_activity_goods (user_id, activity_id, goods_id)
);

-- MQ消费幂等记录表：message_id唯一索引兜底，即使Redis去重记录丢失也不会重复处理
CREATE TABLE mq_consume_record (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id   VARCHAR(64) NOT NULL,
    biz_type     VARCHAR(32) NOT NULL,
    status       TINYINT     NOT NULL COMMENT '0处理中 1已完成',
    create_time  DATETIME    NOT NULL,
    UNIQUE KEY uk_message_id (message_id)
);
```

---

## 四、完整实战案例（带真实数字，从预热到对账走一遍全流程）

### 场景设定

- 活动 ID：`9001`，商品 ID：`3001`（iPhone 秒杀），总库存 **3 件**，每人限购 **1 件**
- 4 个用户参与：`U1001`、`U1002`、`U1003`（手抖点击了 2 次）、`U1004`
- Redis key 具体值全程展示

### 步骤 1：活动预热

```
SET seckill:stock:9001:3001 3
```

此时 `seckill:stock:9001:3001 = 3`，`seckill:limit:9001:3001:*` 还都不存在。

### 步骤 2：并发抢购（Redis 层）

Redis 单线程串行执行 Lua 脚本，5 个请求（U1001、U1002、U1003 的两次点击、U1004）按到达顺序假设为：`U1001 -> U1002 -> U1003(第1次) -> U1004 -> U1003(第2次)`，逐条执行 2.1 节的脚本：

| 顺序 | 请求 | limit key 是否存在 | 库存判断 | 执行结果 | 库存变化 |
|---|---|---|---|---|---|
| 1 | U1001 | 不存在 | 3>0 | 返回1 成功，写入 limit:U1001 | 3 → 2 |
| 2 | U1002 | 不存在 | 2>0 | 返回1 成功，写入 limit:U1002 | 2 → 1 |
| 3 | U1003(第1次) | 不存在 | 1>0 | 返回1 成功，写入 limit:U1003 | 1 → 0 |
| 4 | U1004 | 不存在 | 0，不满足>0 | 返回-1 库存不足 | 不变，仍是0 |
| 5 | U1003(第2次) | **已存在**（第3步写入过） | 不再判断库存 | 返回-2 您已参与过 | 不变，仍是0 |

**这一张表直接说明了两件事**：
- U1004 拿到的是"库存不足"（返回-1），库存最终停在 0，不会被减成负数——**这就是超卖被拦住的过程**。
- U1003 手抖点击的第二次请求，因为 `limit:U1003` 这个 key 已经在第一次成功时写入，直接被第一步的 `EXISTS` 判断拦下，根本没有进入库存判断——**这就是超买被拦住的过程**，而且因为限购判断在库存判断之前，还顺带省了一次无意义的库存读取。

### 步骤 3：异步下单（MQ + 数据库层）

Redis 侧成功的 3 个请求（U1001、U1002、U1003）各自生成一条消息投递到 MQ，消费者依次处理，以 U1001 为例：

```sql
-- 3.1 幂等校验：这条消息之前没处理过才能继续
INSERT INTO mq_consume_record(message_id, biz_type, status, create_time)
VALUES ('msg_u1001_9001_3001', 'seckill_order', 1, NOW());
-- 如果 message_id 已存在，这里会因为唯一索引冲突直接insert失败，说明是重复消息，直接跳过后续步骤

-- 3.2 数据库库存扣减（条件更新，天然原子）
UPDATE seckill_stock SET stock = stock - 1, version = version + 1
WHERE activity_id = 9001 AND goods_id = 3001 AND stock > 0;
-- 受影响行数=1，扣减成功；如果=0说明数据库库存已经不足（正常不该出现，除非和Redis已经存在偏差）

-- 3.3 插入订单
INSERT INTO seckill_order(order_no, user_id, activity_id, goods_id, status, amount, create_time)
VALUES ('SK20260820U1001', 1001, 9001, 3001, 0, 5999.00, NOW());
-- uk_user_activity_goods(user_id, activity_id, goods_id) 唯一索引是最后一道兜底
-- 正常流程走不到这里报错，因为Redis已经提前拦截了U1003的第二次请求
```

U1002、U1003 的处理完全一样，最终 `seckill_stock.stock` 从 3 变成 0，`seckill_order` 表里有 3 条记录，`user_id` 分别是 1001、1002、1003。

### 步骤 4：模拟异常——U1002 的消息消费失败，制造"幽灵库存"

假设 U1002 对应的消息在消费者处理时机器宕机，消息没有被 ACK，经过几次重试后仍然失败，最终进入死信队列。这时候真实状态是：

- Redis：`seckill:stock:9001:3001 = 0`（U1001、U1002、U1003 都已经在 Redis 层扣减过）
- 数据库：`seckill_stock.stock` 只被扣减了 2 次（U1001、U1003 成功入库），`seckill_order` 表里只有 2 条订单

### 步骤 5：定时对账，发现偏差并补偿

对账任务的核对公式：

```
Redis剩余库存 + 数据库有效订单数 应等于 预热总库存
0 (Redis剩余) + 2 (DB订单数) = 2，但预热总库存是3，差了1
```

发现差 1，说明存在 1 份"幽灵库存"（Redis 扣了，但数据库没对应订单）。对账任务进一步排查死信队列，定位到是 U1002 这条消息消费失败，执行补偿：

```
-- 把U1002预扣的库存还回去
INCR seckill:stock:9001:3001          -- 0 -> 1
-- 释放U1002的限购标记，让他可以重新参与（或者走人工补偿通知用户）
DEL seckill:limit:9001:3001:1002
```

补偿完成后，Redis 库存恢复成 1，可以重新放出来给别人抢，或者根据业务策略直接给 U1002 发补偿优惠券，两种处理方式都能让系统数据重新归于一致。

### 步骤 6：模拟订单超时——U1001 15 分钟未支付，触发回滚

```sql
-- 6.1 状态机校验(乐观锁CAS)：只有确实还是待支付状态才允许取消，防止和支付回调并发冲突
UPDATE seckill_order SET status = 2
WHERE order_no = 'SK20260820U1001' AND status = 0;
-- 受影响行数=1才继续；如果=0说明订单已经被支付回调改成已支付了，直接放弃这次取消

-- 6.2 同一个事务里把数据库库存加回去
UPDATE seckill_stock SET stock = stock + 1
WHERE activity_id = 9001 AND goods_id = 3001;
```

数据库事务提交成功后，异步发一条携带订单号 `SK20260820U1001` 的回滚事件到 MQ，Redis 侧消费者执行：

```
-- 幂等判断：这个订单号是否已经回滚过
SADD seckill:rollback:9001:3001 SK20260820U1001
-- 返回1表示第一次处理，可以继续；如果返回0说明已经处理过（比如消息被重复投递），直接跳过下面两步

-- 首次处理才执行：
INCR seckill:stock:9001:3001              -- 库存加回
DEL seckill:limit:9001:3001:1001          -- 释放U1001的限购标记
```

如果这条回滚消息因为网络问题被重复投递了两次，`SADD` 第二次会返回 `0`，直接被拦下，不会出现"库存被加回两次，凭空多出一份库存"的问题——这就是幂等设计在回滚链路上的作用。

---

## 五、这份案例覆盖的知识点小结

| 问题 | 对应的具体机制 | 案例中的体现 |
|---|---|---|
| 超卖 | Lua 脚本原子判断库存+扣减 | U1004 请求时库存已经是0，被脚本内的判断直接拦下，返回-1 |
| 超买 | Lua 脚本内先判断 limit key 是否存在 | U1003 第二次点击被 limit:U1003 已存在拦下，返回-2 |
| 数据库最后兜底 | 条件更新 `where stock>0`、唯一索引 `(user_id,activity_id,goods_id)` | 正常流程走不到这两道防线触发的场景，但异常穿透时能兜住 |
| MQ 消费幂等 | `mq_consume_record` 表的 `message_id` 唯一索引 | 防止同一条下单消息被重复消费，重复插入两条订单 |
| 幽灵库存的发现与修复 | 定时对账公式 + 死信队列排查 | U1002 消息消费失败，对账任务算出差值为1，定位并补偿 |
| 回滚幂等 | Redis Set 记录已回滚订单号 | U1001 超时回滚事件即使被重复投递，也只会真正执行一次 `INCR` |
