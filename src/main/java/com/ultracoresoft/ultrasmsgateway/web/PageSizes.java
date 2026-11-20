package com.ultracoresoft.ultrasmsgateway.web;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * How many rows a list shows per page: 25 by default, 50 or 100 when asked with {@code ?size=}. Any other size is
 * ignored, so an address cannot ask for a huge page. The size stays in the pager's links (see pagination.html).
 */
public final class PageSizes {

    public static final int DEFAULT = 25;
    public static final List<Integer> CHOICES = List.of(25, 50, 100);

    private PageSizes() {
    }

    /** The asked size when it is one of the choices, otherwise the default. */
    public static int of(Integer size) {
        return size != null && CHOICES.contains(size) ? size : DEFAULT;
    }

    public static PageRequest request(int page, Integer size) {
        return PageRequest.of(Math.max(page, 0), of(size));
    }

    public static PageRequest request(int page, Integer size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), of(size), sort);
    }
}
