package com.whatname.iam.service.hr;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.auth.NamePinyin;

import java.util.Locale;

final class HrLoginName {
    private HrLoginName() {}

    static String initials(String name) {
        StringBuilder result = new StringBuilder();
        for (int cp : name.strip().codePoints().toArray()) {
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                result.append(
                        NamePinyin.variants(new String(Character.toChars(cp)))
                                .getFirst()
                                .charAt(0));
            else if (cp < 128 && Character.isLetterOrDigit(cp)) result.appendCodePoint(cp);
            else if (!Character.isWhitespace(cp))
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        if (result.isEmpty() || result.length() > 58)
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        return result.toString().toLowerCase(Locale.ROOT);
    }
}
