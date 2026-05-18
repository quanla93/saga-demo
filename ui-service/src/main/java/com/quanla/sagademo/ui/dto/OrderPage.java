package com.quanla.sagademo.ui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Minimal projection of Spring Data's Page<T> JSON shape.
 * <p>
 * We don't need every field Spring serializes ({@code pageable}, sort metadata
 * etc.) -- {@code @JsonIgnoreProperties(ignoreUnknown=true)} keeps this DTO
 * resilient if order-service ever swaps PagedModel or adds wrappers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPage(
        List<OrderView> content,
        int number,           // current page (0-based)
        int size,             // page size requested
        int totalPages,
        long totalElements,
        boolean first,
        boolean last
) {
    public int displayPage() { return number + 1; }      // 1-based for humans
    public boolean hasPrev()  { return !first; }
    public boolean hasNext()  { return !last; }
    public int prevPage()     { return Math.max(0, number - 1); }
    public int nextPage()     { return number + 1; }
}
