package com.whatname.iam.dto.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int pageSize, int total) {}
