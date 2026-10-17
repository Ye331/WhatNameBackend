package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NameSearchTokensTest {
    static String key(int n){byte[] b=new byte[32];Arrays.fill(b,(byte)n);return Base64.getEncoder().encodeToString(b);}
    static NameSearchTokens codec(){return new NameSearchTokens(key(3),key(0),key(1),key(2));}
    @Test void everyLiteralSubstringIncludesSingleChineseAndMiddleAndNeverNgramFalsePositive() {
        var c=codec();var all=c.all("张小明");assertEquals(6,all.size());
        for(String value:List.of("张","张小明","小明","小","明"))assertTrue(all.contains(c.token(value)));
        assertFalse(all.contains(c.token("张明")));assertFalse(all.contains(c.token("小张")));
        assertEquals(3,c.all("哈哈哈").size());
    }
    @Test void unicodeNfcCaseAccentAndWildcardRulesAreExplicit() {
        var c=codec();assertEquals(c.all("  José  "),c.all("Jose\u0301"));
        assertFalse(c.all("José").contains(c.token("j")));assertFalse(c.all("José").contains(c.token("Jose")));
        assertTrue(c.all("A%_\\B").contains(c.token("%_\\")));
        assertTrue(c.all("A😀B").contains(c.token("😀")));assertFalse(c.all("A😀B").contains(c.token("AB")));
    }
    @Test void boundedLengthAndMalformedUnicodeFailWithoutFallback() {
        var c=codec();String distinct=java.util.stream.IntStream.range(0,64).mapToObj(i->String.valueOf((char)(0x4e00+i))).reduce("",String::concat);
        assertEquals(2080,c.all(distinct).size());
        for(String value:List.of("", " ","x".repeat(65),"\uD800","\uDC00","Z"+"\u0344".repeat(33)))assertThrows(BusinessException.class,()->c.all(value));
    }
    @Test void missingMalformedOrReusedKeysFailClosedAndGenerationsChange() {
        for(String bad:List.of("","bad",key(0),key(1),key(2))) {
            var c=new NameSearchTokens(bad,key(0),key(1),key(2));
            assertEquals(ErrorCode.INTEGRITY_FAILURE,assertThrows(BusinessException.class,c::generation).errorCode());
        }
        assertNotEquals(codec().generation(),new NameSearchTokens(key(4),key(0),key(1),key(2)).generation());
    }
    @Test void domainSeparationAndNoPlaintextInTokens() {
        var c=codec();assertNotEquals(c.generation(),c.token(""));
        assertNotEquals(SensitiveDataCodec.hmac(Base64.getDecoder().decode(key(3)),"张"),c.token("张"));
        assertTrue(c.all("张三").stream().allMatch(x->x.matches("[0-9a-f]{64}")));
    }
}
