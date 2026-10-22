package com.whatname.iam.dto.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class PageRequest {
    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(500)
    private int pageSize = 20;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long offset() {
        return ((long) page - 1) * pageSize;
    }
}
