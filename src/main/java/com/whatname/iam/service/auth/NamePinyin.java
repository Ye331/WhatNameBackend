package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.*;

import java.util.*;

/** Full-name variants, including polyphonic characters; unsupported Han fails safely. */
public final class NamePinyin {
    private NamePinyin() {}

    public static List<String> variants(String name) {
        var format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        Set<String> values = new LinkedHashSet<>(List.of(""));
        try {
            for (int cp : name.codePoints().toArray()) {
                String[] pieces;
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                    if (cp > Character.MAX_VALUE) throw new IllegalArgumentException();
                    pieces = PinyinHelper.toHanyuPinyinStringArray((char) cp, format);
                    if (pieces == null || pieces.length == 0) throw new IllegalArgumentException();
                } else if (Character.isLetterOrDigit(cp))
                    pieces =
                            new String[] {
                                new String(Character.toChars(cp)).toLowerCase(Locale.ROOT)
                            };
                else continue;
                Set<String> next = new LinkedHashSet<>();
                for (String prefix : values)
                    for (String piece : pieces) {
                        next.add(prefix + piece);
                        if (next.size() > 256) throw new IllegalArgumentException();
                    }
                values = next;
            }
            values.remove("");
            if (values.isEmpty()) throw new IllegalArgumentException();
            return List.copyOf(values);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
    }
}
