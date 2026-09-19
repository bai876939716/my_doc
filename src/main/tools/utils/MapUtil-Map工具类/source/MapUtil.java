package com.jst.aftersale.common.utils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Map 工具类
 * 封装 Map 常用的 Stream 操作，与 ListUtils 配合使用
 *
 * @program: dis-aftersale-service
 * @author: 疆戟
 * @create: 2025-07-14 14:40
 */
public final class MapUtil {

    private MapUtil() {}

    // ==================== 映射操作 ====================

    /**
     * 将 Map 的 Entry 映射为列表
     */
    public static <K, V, E> List<E> map(Map<K, V> map, Function<Map.Entry<K, V>, ? extends E> mapper) {
        if (MapUtils.isEmpty(map)) {
            return new ArrayList<>();
        }
        return map.entrySet().stream().map(mapper)
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * 将 Map 的 Key 和 Value 通过 BiFunction 映射为列表
     */
    public static <K, V, E> List<E> map(Map<K, V> map, BiFunction<K, V, ? extends E> mapper) {
        if (MapUtils.isEmpty(map)) {
            return new ArrayList<>();
        }
        return map.entrySet().stream().map(e -> mapper.apply(e.getKey(), e.getValue()))
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * 提取 Map 所有 Value 为列表
     */
    public static <K, V> List<V> values(Map<K, V> map) {
        if (MapUtils.isEmpty(map)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 提取 Map 所有 Key 为列表
     */
    public static <K, V> List<K> keys(Map<K, V> map) {
        if (MapUtils.isEmpty(map)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(map.keySet());
    }

    /**
     * 对 Map 的 Value 进行映射转换，Key 不变
     */
    public static <K, V, R> Map<K, R> mapValues(Map<K, V> map, Function<V, R> valueMapper) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> valueMapper.apply(e.getValue()), (v1, v2) -> v1));
    }

    /**
     * 对 Map 的 Key 进行映射转换，Value 不变
     */
    public static <K, V, R> Map<R, V> mapKeys(Map<K, V> map, Function<K, R> keyMapper) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream()
                .collect(Collectors.toMap(e -> keyMapper.apply(e.getKey()), Map.Entry::getValue, (v1, v2) -> v1));
    }

    // ==================== 过滤操作 ====================

    /**
     * 按 Entry 条件过滤 Map
     */
    public static <K, V> Map<K, V> filter(Map<K, V> map, BiPredicate<K, V> predicate) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream()
                .filter(e -> predicate.test(e.getKey(), e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    /**
     * 按 Key 条件过滤 Map
     */
    public static <K, V> Map<K, V> filterByKey(Map<K, V> map, Predicate<K> predicate) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream()
                .filter(e -> predicate.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    /**
     * 按 Value 条件过滤 Map
     */
    public static <K, V> Map<K, V> filterByValue(Map<K, V> map, Predicate<V> predicate) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream()
                .filter(e -> predicate.test(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    /**
     * 按 Key 集合过滤 Map（保留 Key 在集合中的条目）
     */
    public static <K, V> Map<K, V> filterByKeys(Map<K, V> map, Collection<K> keys) {
        if (MapUtils.isEmpty(map) || CollectionUtils.isEmpty(keys)) {
            return new HashMap<>();
        }
        Set<K> keySet = keys instanceof Set ? (Set<K>) keys : new java.util.HashSet<>(keys);
        return map.entrySet().stream()
                .filter(e -> keySet.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1));
    }

    // ==================== 展平操作 ====================

    /**
     * 将 Map 展平为列表
     */
    public static <K, V, R> List<R> flatMap(Map<K, V> map, BiFunction<K, V, ? extends Stream<? extends R>> mapper) {
        if (MapUtils.isEmpty(map)) {
            return new ArrayList<>();
        }
        return map.entrySet().stream()
                .flatMap(e -> mapper.apply(e.getKey(), e.getValue()))
                .filter(Objects::nonNull)
                .collect(toList());
    }

    /**
     * 将 Map<K, List<V>> 的所有 Value 列表展平为一个列表
     */
    public static <K, V> List<V> flatValues(Map<K, ? extends Collection<V>> map) {
        if (MapUtils.isEmpty(map)) {
            return new ArrayList<>();
        }
        return map.values().stream()
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .collect(toList());
    }

    // ==================== 合并操作 ====================

    /**
     * 合并两个 Map，Key 冲突时使用 mergeFunction 处理
     */
    public static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2, BiFunction<V, V, V> mergeFunction) {
        Map<K, V> result = new HashMap<>();
        if (MapUtils.isNotEmpty(map1)) {
            result.putAll(map1);
        }
        if (MapUtils.isNotEmpty(map2)) {
            map2.forEach((key, value) -> result.merge(key, value, mergeFunction));
        }
        return result;
    }

    /**
     * 合并两个 Map，Key 冲突时保留第一个
     */
    public static <K, V> Map<K, V> merge(Map<K, V> map1, Map<K, V> map2) {
        return merge(map1, map2, (v1, v2) -> v1);
    }

    // ==================== 安全取值 ====================

    /**
     * 安全获取 Map 中的值，不存在时返回默认值
     */
    public static <K, V> V getOrDefault(Map<K, V> map, K key, V defaultValue) {
        if (MapUtils.isEmpty(map)) {
            return defaultValue;
        }
        V value = map.get(key);
        return Objects.nonNull(value) ? value : defaultValue;
    }

    /**
     * 安全获取 Map 中的值并映射，不存在时返回默认值
     */
    public static <K, V, R> R getAndMap(Map<K, V> map, K key, Function<V, R> mapper, R defaultValue) {
        if (MapUtils.isEmpty(map)) {
            return defaultValue;
        }
        V value = map.get(key);
        if (Objects.isNull(value)) {
            return defaultValue;
        }
        return mapper.apply(value);
    }

    /**
     * computeIfAbsent 的安全版本（兼容 Java 8 性能问题 JDK-8161372）
     */
    public static <K, V> V computeIfAbsent(Map<K, V> map, K key, Function<K, V> mappingFunction) {
        V value = map.get(key);
        if (value != null) {
            return value;
        }
        return map.computeIfAbsent(key, mappingFunction::apply);
    }

    // ==================== 遍历操作 ====================

    /**
     * 安全遍历 Map
     */
    public static <K, V> void forEach(Map<K, V> map, BiConsumer<K, V> action) {
        if (MapUtils.isEmpty(map)) {
            return;
        }
        map.forEach(action);
    }

    // ==================== 转换操作 ====================

    /**
     * 反转 Map 的 Key 和 Value
     * 注意：如果有重复 Value，只保留第一个
     */
    public static <K, V> Map<V, K> invert(Map<K, V> map) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        return map.entrySet().stream()
                .filter(e -> Objects.nonNull(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (v1, v2) -> v1));
    }

    /**
     * 将 Map<K, List<V>> 反转为 Map<V, K>
     * 即将分组结果展开为每个元素到其分组 Key 的映射
     */
    public static <K, V> Map<V, K> invertGrouping(Map<K, ? extends Collection<V>> map) {
        if (MapUtils.isEmpty(map)) {
            return new HashMap<>();
        }
        Map<V, K> result = new HashMap<>();
        map.forEach((key, values) -> {
            if (CollectionUtils.isNotEmpty(values)) {
                values.forEach(value -> result.putIfAbsent(value, key));
            }
        });
        return result;
    }

    /**
     * 判断 Map 是否为空（null 或 size=0）
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return MapUtils.isEmpty(map);
    }

    /**
     * 判断 Map 是否非空
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return MapUtils.isNotEmpty(map);
    }
}
