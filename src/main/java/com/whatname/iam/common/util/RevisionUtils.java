package com.whatname.iam.common.util;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RevisionUtils {
    private static final Pattern REVISION_PATTERN = Pattern.compile("^v([1-9]\\d*)$");

    private RevisionUtils() {}

    public static String toVersion(int revision) {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return "v" + revision;
    }

    public static int parseExpectedVersion(String expectedVersion) {
        if (expectedVersion == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        Matcher matcher = REVISION_PATTERN.matcher(expectedVersion.trim());
        if (!matcher.matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
