# MapUtil Map 工具类

## 1. 概述

`MapUtil` 提供对 `Map` 集合的便捷操作，封装了映射、过滤、展平、合并、安全取值等常用 Stream 操作，与 `ListUtils` 配合使用，覆盖日常开发中 Map 场景的简化需求。所有方法对 `null` / 空 Map 安全处理。

**所属模块：** `com.jst.aftersale.common.utils`  
**作者：** 疆戟 | **创建日期：** 2025-07-14

---

## 2. 方法总览

| 方法名 | 功能 | 返回类型 | 分类 |
|--------|------|---------|------|
| `map(Map, Function<Entry>)` | Entry 映射为列表 | `List<E>` | 映射 |
| `map(Map, BiFunction<K,V>)` | Key+Value 映射为列表 | `List<E>` | 映射 |
| `values` | 提取所有 Value | `List<V>` | 映射 |
| `keys` | 提取所有 Key | `List<K>` | 映射 |
| `mapValues` | Value 映射转换，Key 不变 | `Map<K,R>` | 映射 |
| `mapKeys` | Key 映射转换，Value 不变 | `Map<R,V>` | 映射 |
| `filter` | 按 Key+Value 条件过滤 | `Map<K,V>` | 过滤 |
| `filterByKey` | 按 Key 条件过滤 | `Map<K,V>` | 过滤 |
| `filterByValue` | 按 Value 条件过滤 | `Map<K,V>` | 过滤 |
| `filterByKeys` | 按 Key 集合过滤 | `Map<K,V>` | 过滤 |
| `flatMap` | 展平为列表 | `List<R>` | 展平 |
| `flatValues` | 展平所有 Value 列表 | `List<V>` | 展平 |
| `merge(map1, map2, mergeFunc)` | 合并两个 Map | `Map<K,V>` | 合并 |
| `merge(map1, map2)` | 合并（冲突保留第一个） | `Map<K,V>` | 合并 |
| `getOrDefault` | 安全取值 | `V` | 取值 |
| `getAndMap` | 取值并映射 | `R` | 取值 |
| `computeIfAbsent` | 安全 computeIfAbsent | `V` | 取值 |
| `forEach` | 安全遍历 | `void` | 遍历 |
| `invert` | 反转 Key/Value | `Map<V,K>` | 转换 |
| `invertGrouping` | 反转分组 Map | `Map<V,K>` | 转换 |
| `isEmpty` / `isNotEmpty` | 空判断 | `boolean` | 工具 |

---

## 3. 使用示例

### 3.1 映射操作

```java
Map<Long, User> userMap = ...;

// Entry 映射：构建 "ID:Name" 列表
List<String> labels = MapUtil.map(userMap, entry -> entry.getKey() + ":" + entry.getValue().getName());

// BiFunction 映射：更直观
List<String> labels2 = MapUtil.map(userMap, (id, user) -> id + ":" + user.getName());

// 提取所有 Value
List<User> allUsers = MapUtil.values(userMap);

// Value 映射：Map<Long, User> → Map<Long, String>
Map<Long, String> nameMap = MapUtil.mapValues(userMap, User::getName);

// Key 映射：Map<Long, User> → Map<String, User>
Map<String, User> strKeyMap = MapUtil.mapKeys(userMap, String::valueOf);
```

### 3.2 过滤操作

```java
Map<Integer, Order> orderMap = ...;

// 按 Key+Value 过滤：保留金额 > 1000 的订单
Map<Integer, Order> highValue = MapUtil.filter(orderMap, (id, order) -> order.getAmount() > 1000);

// 按 Key 过滤
Map<Integer, Order> filtered = MapUtil.filterByKey(orderMap, id -> id > 100);

// 按 Value 过滤
Map<Integer, Order> active = MapUtil.filterByValue(orderMap, Order::isActive);

// 按 Key 集合过滤：只保留指定 ID 的订单
Set<Integer> targetIds = Set.of(1001, 1002, 1003);
Map<Integer, Order> targets = MapUtil.filterByKeys(orderMap, targetIds);
```

### 3.3 展平操作

```java
Map<String, List<Order>> deptOrders = ...;

// 展平所有部门的订单为一个列表
List<Order> allOrders = MapUtil.flatValues(deptOrders);

// 展平并映射
List<String> allOrderIds = MapUtil.flatMap(deptOrders, (dept, orders) -> orders.stream().map(Order::getId));
```

### 3.4 合并操作

```java
Map<String, Integer> map1 = Map.of("A", 10, "B", 20);
Map<String, Integer> map2 = Map.of("B", 30, "C", 40);

// 合并，冲突时求和
Map<String, Integer> merged = MapUtil.merge(map1, map2, Integer::sum);
// 结果: {"A": 10, "B": 50, "C": 40}

// 合并，冲突时保留第一个
Map<String, Integer> merged2 = MapUtil.merge(map1, map2);
// 结果: {"A": 10, "B": 20, "C": 40}
```

### 3.5 安全取值

```java
Map<Long, User> userMap = ...;

// 安全取值
User user = MapUtil.getOrDefault(userMap, 999L, User.EMPTY);

// 取值并映射
String name = MapUtil.getAndMap(userMap, 1L, User::getName, "未知用户");

// computeIfAbsent（兼容 Java 8 性能问题）
Map<String, List<Order>> groupMap = new HashMap<>();
List<Order> orders = MapUtil.computeIfAbsent(groupMap, "dept1", k -> new ArrayList<>());
orders.add(newOrder);
```

### 3.6 转换操作

```java
Map<Long, String> idToName = Map.of(1L, "张三", 2L, "李四");

// 反转：Map<Long, String> → Map<String, Long>
Map<String, Long> nameToId = MapUtil.invert(idToName);
// 结果: {"张三": 1L, "李四": 2L}

// 反转分组：Map<String, List<Long>> → Map<Long, String>
Map<String, List<Long>> deptToUserIds = Map.of("销售部", List.of(1L, 2L), "技术部", List.of(3L));
Map<Long, String> userIdToDept = MapUtil.invertGrouping(deptToUserIds);
// 结果: {1L: "销售部", 2L: "销售部", 3L: "技术部"}
```

---

## 4. 设计特点

1. **Null 安全**：所有方法入参 Map 为 null 或空时返回空集合，不抛异常
2. **自动过滤 null**：映射结果中的 null 值自动过滤
3. **键冲突处理**：所有产生 Map 的方法默认 `(v1, v2) -> v1` 保留第一个
4. **两种映射风格**：`Function<Entry>` 和 `BiFunction<K,V>` 满足不同偏好
5. **兼容性**：`computeIfAbsent` 兼容 Java 8 性能问题 JDK-8161372
6. **与 ListUtils 互补**：ListUtils 侧重 List→Map/List，MapUtil 侧重 Map→List/Map

---

## 5. 与 ListUtils 的配合

| 场景 | 使用工具 |
|------|---------|
| `List<T>` → `Map<K,V>` | `ListUtils.toMap(list, keyMapper, valueMapper)` |
| `List<T>` → `Map<K, List<V>>` | `ListUtils.groupingBy(list, keyMapper)` |
| `Map<K,V>` → `List<R>` | `MapUtil.map(map, mapper)` |
| `Map<K, List<V>>` → `List<V>` | `MapUtil.flatValues(map)` |
| `Map<K,V>` → `Map<K,R>` | `MapUtil.mapValues(map, valueMapper)` |
| `Map<K,V>` 展平为 Stream | `ListUtils.flatMapWithMapToStream(map, mapper)` |

---

## 6. 源码

源码位于 [source/](./source/) 目录下。

**原始代码路径：** `dis-aftersale-service/src/main/java/com/jst/aftersale/common/utils/MapUtil.java`

> 注：当前项目中 MapUtil 仅包含 2 个 `map` 方法，本文档中的扩展方法为参考 ListUtils 风格补充的建议实现，可按需引入。
