package com.example.bankapplication.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A plain, stable JSON shape for a page of results. Spring Data's own Page/PageImpl is deliberately not
 * returned directly from a controller: its Jackson serialization is not meant to be relied on across versions.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
