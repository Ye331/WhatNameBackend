package com.whatname.iam.common.security;

import com.whatname.iam.common.exception.BusinessException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataCodecTest {
    static String key(int value) { byte[] b = new byte[32]; java.util.Arrays.fill(b, (byte)value); return Base64.getEncoder().encodeToString(b); }
    @Test void authenticatedRandomRoundTripAndStableIndependentPhoneIndex() {
        var c = new SensitiveDataCodec(key(1), key(2));
        String a = c.encrypt("测试😀"), b = c.encrypt("测试😀");
        assertNotEquals(a,b); assertEquals("测试😀",c.decrypt(a));
        assertTrue(a.startsWith("gcm1:"));
        assertEquals(64,c.phoneHash("13800000005").length());
        assertEquals(c.phoneHash("13800000005"),c.phoneHash("13800000005"));
        assertNotEquals(c.phoneHash("13800000005"),new SensitiveDataCodec(key(1),key(3)).phoneHash("13800000005"));
    }
    @Test void tamperingWrongKeysAndMissingConfigurationFailClosed() {
        var c = new SensitiveDataCodec(key(1),key(2));
        String a = c.encrypt("secret");
        assertThrows(BusinessException.class,()->new SensitiveDataCodec(key(3),key(2)).decrypt(a));
        assertThrows(BusinessException.class,()->c.decrypt(a.substring(0,12)+"X"+a.substring(13)));
        assertThrows(BusinessException.class,()->c.decrypt("plaintext"));
        assertThrows(BusinessException.class,()->new SensitiveDataCodec("","").encrypt("x"));
        assertThrows(BusinessException.class,()->new SensitiveDataCodec(key(1),key(1)).phoneHash("13800000005"));
    }
    @Test void phoneCanonicalizationIsExplicitAndBounded() {
        assertEquals("13800000005",SensitiveDataCodec.normalizePhone(" +8613800000005 "));
        for(String p : new String[]{"138-0000-0005","123","0013800000005","138000000051"})
            assertThrows(BusinessException.class,()->SensitiveDataCodec.normalizePhone(p));
    }
}
