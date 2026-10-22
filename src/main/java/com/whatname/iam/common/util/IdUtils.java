package com.whatname.iam.common.util;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import com.whatname.iam.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 接口 ID 使用不带前导零的正整数十进制字符串。 */
public final class IdUtils {
    private IdUtils() {}

    public static long parse(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(INVALID_ARGUMENT);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(INVALID_ARGUMENT);
        }
    }

    /** 保留提交顺序、去除重复 ID；空集合表示清空当前映射。 */
    public static List<Long> distinct(List<Long> source) {
        if (source == null) return List.of();
        var ids = new LinkedHashSet<Long>();
        for (Long id : source) {
            if (id == null || id <= 0) throw new BusinessException(INVALID_ARGUMENT);
            ids.add(id);
        }
        return new ArrayList<>(ids);
    }
}
