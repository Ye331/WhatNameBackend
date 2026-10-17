package com.whatname.iam.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SmsCodeHasher {
    private final String key;
    public SmsCodeHasher(@Value("${iam.security.sms-hmac-key:}") String key) { this.key=key; }
    public String hash(String phoneHash,String purpose,String code) {
        return SensitiveDataCodec.hmac(SensitiveDataCodec.requiredKey(key), "sms1\n"+phoneHash+"\n"+purpose+"\n"+code);
    }
    public boolean matches(String phoneHash,String purpose,String code,String expected) {
        return MessageDigest.isEqual(hash(phoneHash,purpose,code).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }
}
