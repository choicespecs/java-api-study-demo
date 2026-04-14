package com.example.apidemo.dto;

import lombok.*;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * CONCEPT: Pagination Response
 *
 * Always include metadata when paginating:
 *   - currentPage / totalPages: lets clients know where they are
 *   - totalElements: total record count without fetching all
 *   - pageSize: how many per page
 *   - hasNext / hasPrevious: simplifies client-side navigation
 *
 * This avoids the anti-pattern of returning unbounded lists.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> content;
    private int currentPage;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;

    public static <T> PagedResponse<T> of(Page<T> page) {
        return PagedResponse.<T>builder()
                .content(page.getContent())
                .currentPage(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}
