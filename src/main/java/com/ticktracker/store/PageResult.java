package com.ticktracker.store;

import java.util.List;
import java.util.function.Function;

/** One page of results plus the numbers a client needs to page through the rest. */
public record PageResult<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResult<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size == 0 ? 0 : (int) ((totalItems + size - 1) / size);
        return new PageResult<>(List.copyOf(items), page, size, totalItems, totalPages);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), page, size, totalItems, totalPages);
    }
}
