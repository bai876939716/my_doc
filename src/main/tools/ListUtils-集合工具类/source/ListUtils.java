package com.jst.aftersale.common.utils;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.jst.aftersale.common.constant.Constant;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * @Description: List工具
 * @Author 疆戟
 * @Date 2024/8/20 23:14
 * @Version 1.0
 */
public final class ListUtils {

    public static <T, R> List<R> map(List<T> list, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().map(mapper).filter(Objects::nonNull).collect(toList());
    }

    public static <T, R> List<R> map(Set<T> set, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(set)) { return new ArrayList<>(); }
        return set.stream().map(mapper).filter(Objects::nonNull).collect(toList());
    }

    public static <T, R> List<R> filterMap(List<T> list, Predicate<? super T> filter, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().filter(Objects::nonNull).filter(filter).map(mapper).collect(toList());
    }

    public static <T, R> Set<R> filterMapToSet(List<T> list, Predicate<? super T> filter, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(list)) { return Sets.newHashSet(); }
        return list.stream().filter(Objects::nonNull).filter(filter).map(mapper).collect(toSet());
    }

    public static <T, R> List<R> filterMapDistinct(List<T> list, Predicate<? super T> filter, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().filter(Objects::nonNull).filter(filter).map(mapper).collect(toList());
    }

    public static <T, R, E> List<E> mapBuild(List<T> list, Function<? super T, ? extends R> mapper, Function<? super R, ? extends E> buildMapper) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().map(mapper).filter(Objects::nonNull).map(buildMapper).collect(toList());
    }

    public static <T, R> List<R> mapDistinct(List<T> list, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().map(mapper).filter(Objects::nonNull).distinct().collect(toList());
    }

    public static <T, R> Set<R> mapToSet(List<T> list, Function<? super T, ? extends R> mapper) {
        if (Objects.isNull(list)) { return new HashSet<>(); }
        return list.stream().map(mapper).filter(Objects::nonNull).collect(toSet());
    }

    public static <K, V> Map<K, List<V>> groupingBy(List<V> list, Function<? super V, ? extends K> keyMapper) {
        if (Objects.isNull(list)) { return new HashMap<>(); }
        return list.stream().collect(Collectors.groupingBy(keyMapper));
    }

    public static <K, V, U> Map<K, List<U>> groupingBy(List<V> list, Function<? super V, ? extends K> keyMapper, Function<V, U> mapper) {
        if (Objects.isNull(list)) { return new HashMap<>(); }
        return list.stream().collect(Collectors.groupingBy(keyMapper, Collectors.mapping(mapper, Collectors.toList())));
    }

    public static <K, V, U> Map<K, Set<U>> groupingByReturnSet(List<V> list, Function<? super V, ? extends K> keyMapper, Function<V, U> mapper) {
        if (Objects.isNull(list)) { return new HashMap<>(); }
        return list.stream().collect(Collectors.groupingBy(keyMapper, Collectors.mapping(mapper, Collectors.toSet())));
    }

    public static <T> List<T> filter(List<T> list, Predicate<? super T> predicate) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().filter(predicate).collect(toList());
    }

    public static <T> T findAny(List<T> list, Predicate<? super T> predicate) {
        if (Objects.isNull(list)) { return null; }
        return list.stream().filter(predicate).findAny().orElse(null);
    }

    public static <T> List<T> filter(Set<T> list, Predicate<? super T> predicate) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().filter(predicate).collect(toList());
    }

    public static <T, K> Map<K, T> toMap(List<T> list, Function<? super T, ? extends K> keyMapper) {
        return toMap(list, keyMapper, Function.identity(), (v1, v2) -> v1);
    }

    public static <T, K, V> Map<K, V> toMap(List<T> list, Function<? super T, ? extends K> keyMapper,
                                            Function<? super T, ? extends V> valueMapper) {
        return toMap(list, keyMapper, valueMapper, (v1, v2) -> v1);
    }

    public static <T, K, V> Map<K, V> toMap(List<T> list, Function<? super T, ? extends K> keyMapper,
                                            Function<? super T, ? extends V> valueMapper,
                                            BinaryOperator<V> mergeFunction) {
        if (CollectionUtils.isEmpty(list)) { return new HashMap<>(); }
        return list.stream().collect(Collectors.toMap(keyMapper, valueMapper, mergeFunction));
    }

    public static <T, R> List<R> flatMapDistinct(List<T> list, Function<? super T, ? extends Stream<? extends R>> mapper) {
        if (Objects.isNull(list)) { return new ArrayList<>(); }
        return list.stream().flatMap(mapper).filter(Objects::nonNull).distinct().collect(toList());
    }

    public static <K, V, R> List<R> flatMapWithMap(Map<K, V> map, Function<Map.Entry<K, V>, ? extends Stream<? extends R>> mapper) {
        if (MapUtils.isEmpty(map)) { return new ArrayList<>(); }
        return map.entrySet().stream().flatMap(mapper).filter(Objects::nonNull).collect(toList());
    }

    public static <T, R> Stream<R> flatMapToStream(List<T> list, Function<? super T, ? extends Stream<? extends R>> mapper) {
        if (Objects.isNull(list)) { return Stream.empty(); }
        return list.stream().flatMap(mapper).filter(Objects::nonNull).distinct();
    }

    public static <T> List<T> subtract(List<T> sourceData, Collection<T> comparisonData) {
        if (CollectionUtils.isEmpty(sourceData)) { return new ArrayList<>(); }
        if (CollectionUtils.isEmpty(comparisonData)) { return sourceData; }
        return sourceData.stream().filter(element -> !comparisonData.contains(element)).collect(Collectors.toList());
    }

    public static <T> List<T> ofNullable(List<T> source, List<T> defaultValue) {
        return CollectionUtils.isEmpty(source) ? defaultValue : source;
    }

    public static <T> Stream<T> ofNullable(List<T> source, Stream<T> defaultValue) {
        return CollectionUtils.isEmpty(source) ? defaultValue : source.stream();
    }

    public static <T> List<List<T>> partition(List<T> list, int size) {
        return org.apache.commons.collections4.ListUtils.partition(list, size);
    }

    public static <T> String joining(List<T> list, Function<? super T, ? extends String> mapper) {
        if (CollectionUtils.isEmpty(list)) { return StringUtils.EMPTY; }
        return list.stream().map(mapper).collect(Collectors.joining(Constant.SEMICOLON));
    }

    public static <T> T getFirst(List<T> list, T defaultValue) {
        if (CollectionUtils.isEmpty(list)) { return defaultValue; }
        return list.get(Constant.ZERO);
    }

    public static <T, K, V> Map<K, V> filterToMap(List<T> list, Predicate<? super T> filter,
                                            Function<? super T, ? extends K> keyMapper,
                                            Function<? super T, ? extends V> valueMapper) {
        if (Objects.isNull(list)) { return Maps.newHashMap(); }
        return list.stream().filter(Objects::nonNull).filter(filter)
                .collect(Collectors.toMap(keyMapper, valueMapper, (v1, v2) -> v1));
    }

    public static <T> List<T> merge(List<T>... failList) {
        if (ArrayUtils.isEmpty(failList)) { return new ArrayList<>(); }
        return Arrays.stream(failList).filter(Objects::nonNull).flatMap(Collection::stream).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public static <T> List<T> distinct(List<T> list) {
        if (CollectionUtils.isEmpty(list)) { return list; }
        return list.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    public static <T> boolean contains(List<T> list, T t) {
        if (CollectionUtils.isEmpty(list)) { return false; }
        return list.contains(t);
    }

    public static int size(List<?> list) {
        if (CollectionUtils.isEmpty(list)) { return 0; }
        return list.size();
    }
}
