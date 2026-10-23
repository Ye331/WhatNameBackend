package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

/** 演示手机号规范化；不加密、不生成查询散列。 */
public final class PhoneNumbers {
    private PhoneNumbers() {}

    public static String normalize(String phone) {
        if (phone == null) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        String value = phone.strip();
        if (value.startsWith("+86")) value = value.substring(3);
        if (!value.matches("1[3-9][0-9]{9}"))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        return value;
    }
}
