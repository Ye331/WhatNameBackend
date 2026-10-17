package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Full substring tokens, not n-gram candidates: no false-positive post-page filtering. */
@Component
public final class NameSearchTokens {
    public static final String VERSION="nfc-cs-substring-v1";
    private final String key, dataKey, phoneKey, smsKey;
    public NameSearchTokens(@Value("${iam.security.name-search-key:}") String key,
            @Value("${iam.security.data-key:}") String dataKey,
            @Value("${iam.security.phone-hmac-key:}") String phoneKey,
            @Value("${iam.security.sms-hmac-key:}") String smsKey) {
        this.key=key;this.dataKey=dataKey;this.phoneKey=phoneKey;this.smsKey=smsKey;
    }
    private byte[] key() {
        byte[] result=SensitiveDataCodec.requiredKey(key);
        for(String other:List.of(dataKey,phoneKey,smsKey)) {
            if(!other.isBlank() && MessageDigest.isEqual(result,SensitiveDataCodec.requiredKey(other)))
                throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
        return result;
    }
    public String generation() { return SensitiveDataCodec.hmac(key(),"whatname:name-search:generation:"+VERSION); }
    public String token(String normalized) { return SensitiveDataCodec.hmac(key(),"whatname:name-search:substring:"+VERSION+":"+normalized); }
    /** NFC, strip outer Java whitespace, case/accent sensitive; never split surrogate pairs. */
    public static String normalize(String value) {
        if(value==null || value.length()>64) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            } else if(Character.isLowSurrogate(c)) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String normalized=Normalizer.normalize(value.strip(),Normalizer.Form.NFC);
        // Canonical composition exclusions can expand even an input <=64 UTF-16 units.
        if(normalized.codePointCount(0,normalized.length())>64) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        return normalized;
    }
    public Set<String> all(String name) {
        String normalized=normalize(name);
        if(normalized.isEmpty()) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        int[] points=normalized.codePoints().toArray();
        Set<String> result=new TreeSet<>();
        // At most 64*65/2=2080 distinct 256-bit tokens per name.
        for(int start=0;start<points.length;start++) for(int end=start+1;end<=points.length;end++)
            result.add(token(new String(points,start,end-start)));
        return result;
    }
}
