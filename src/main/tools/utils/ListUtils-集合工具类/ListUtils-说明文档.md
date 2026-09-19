# ListUtils 集合工具类

## 1. 概述

`ListUtils` 封装了 Java Stream API 的常用操作，提供便捷的集合处理方法，简化日常开发中的集合操作代码。所有方法对 `null` 输入安全处理，不会抛出 `NullPointerException`。

**所属模块：** `com.jst.aftersale.common.utils`  
**作者：** 疆戟 | **版本：** 1.0

---

## 2. 方法总览

| 方法名 | 功能 | 返回类型 | 分类 |
|--------|------|---------|------|
| `map` | 映射转换（自动过滤 null） | `List<R>` | 映射 |
| `mapDistinct` | 映射并去重 | `List<R>` | 映射 |
| `mapToSet` | 映射为 Set | `Set<R>` | 映射 |
| `mapBuild` | 连续两次映射 | `List<E>` | 映射 |
| `filter` | 过滤元素 | `List<T>` | 过滤 |
| `findAny` | 查找任意匹配元素 | `T` | 过滤 |
| `filterMap` | 过滤后映射 | `List<R>` | 过滤 |
| `filterMapToSet` | 过滤后映射为 Set | `Set<R>` | 过滤 |
| `filterToMap` | 过滤后转为 Map | `Map<K,V>` | 过滤 |
| `toMap` | 转为 Map（3 个重载） | `Map<K,V>` | 转换 |
| `groupingBy` | 分组（3 个重载） | `Map<K,List<V>>` | 分组 |
| `flatMapDistinct` | 展平并去重 | `List<R>` | 展平 |
| `flatMapWithMap` | Map 展平 | `List<R>` | 展平 |
| `subtract` | 差集 | `List<T>` | 集合运算 |
| `partition` | 拆分为子列表 | `List<List<T>>` | 集合运算 |
| `merge` | 合并多个 List | `List<T>` | 集合运算 |
| `distinct` | 去重 | `List<T>` | 集合运算 |
| `joining` | 连接为字符串（分号分隔） | `String` | 工具 |
| `getFirst` | 安全获取第一个元素 | `T` | 工具 |
| `ofNullable` | 空值安全包装 | `List<T>` / `Stream<T>` | 工具 |
| `contains` | 判断是否包含 | `boolean` | 工具 |
| `size` | 安全获取长度 | `int` | 工具 |

---

## 3. 使用示例

### 3.1 映射操作

```java
// 提取所有用户 ID
List<Long> userIds = ListUtils.map(users, User::getId);

// 提取并去重
List<String> departments = ListUtils.mapDistinct(users, User::getDepartment);

// 连续映射：User -> Name -> 带前缀
List<String> labels = ListUtils.mapBuild(users, User::getName, name -> "用户：" + name);
```

### 3.2 过滤操作

```java
// 过滤成年用户
List<User> adults = ListUtils.filter(users, u -> u.getAge() >= 18);

// 过滤后提取姓名
List<String> adultNames = ListUtils.filterMap(users, u -> u.getAge() >= 18, User::getName);

// 过滤后转 Map
Map<Long, String> activeMap = ListUtils.filterToMap(users, User::isActive, User::getId, User::getName);
```

### 3.3 转换操作

```java
// List -> Map（ID 为 Key，对象为 Value）
Map<Long, User> userMap = ListUtils.toMap(users, User::getId);

// List -> Map（指定 Key 和 Value）
Map<Long, String> nameMap = ListUtils.toMap(users, User::getId, User::getName);

// 重复 Key 合并策略
Map<String, Integer> totalMap = ListUtils.toMap(items, Item::getCategory, Item::getQty, Integer::sum);
```

### 3.4 分组操作

```java
// 按部门分组
Map<String, List<User>> deptMap = ListUtils.groupingBy(users, User::getDepartment);

// 按部门分组，只保留姓名
Map<String, List<String>> deptNames = ListUtils.groupingBy(users, User::getDepartment, User::getName);
```

### 3.5 集合运算

```java
// 差集：找出未处理的订单
List<Order> unprocessed = ListUtils.subtract(allOrders, processedOrders);

// 拆分为每批 100 条
List<List<Order>> batches = ListUtils.partition(orders, 100);
for (List<Order> batch : batches) {
    processBatch(batch);
}

// 合并多个列表
List<Item> all = ListUtils.merge(list1, list2, list3);
```

### 3.6 工具方法

```java
// 安全获取第一个元素
User first = ListUtils.getFirst(users, null);

// 连接为字符串（分号分隔）
String names = ListUtils.joining(users, User::getName);  // "张三;李四;王五"

// 空值安全
List<String> result = ListUtils.ofNullable(maybeNull, Collections.emptyList());
```

---

## 4. 设计特点

1. **Null 安全**：所有方法入参为 null 时返回空集合，不抛异常
2. **自动过滤 null**：`map` 系列方法自动过滤映射结果中的 null 值
3. **键冲突处理**：`toMap` 默认保留第一个值（`(v1, v2) -> v1`）
4. **分隔符固定**：`joining` 使用分号（`;`）作为分隔符
5. **委托实现**：`partition` 委托给 Apache Commons `ListUtils.partition`

---

## 5. 源码

源码位于 [source/](./source/) 目录下。

**原始代码路径：** `dis-aftersale-service/src/main/java/com/jst/aftersale/common/utils/ListUtils.java`
