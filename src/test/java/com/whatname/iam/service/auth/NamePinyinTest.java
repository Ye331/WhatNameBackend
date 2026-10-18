package com.whatname.iam.service.auth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NamePinyinTest {
    @Test void knownChineseLatinAndPolyphonicNamesStayComplete() {
        assertTrue(NamePinyin.variants("张三").contains("zhangsan"));
        assertTrue(NamePinyin.variants(" 单 明 ").contains("shanming"));
        assertTrue(NamePinyin.variants(" 单 明 ").contains("danming"));
        assertEquals(java.util.List.of("alice"),NamePinyin.variants("Alice"));
    }
    @Test void untransliterableHanAndEmptyNamesAreNotSilentlySkipped() {
        assertThrows(com.whatname.iam.common.exception.BusinessException.class,()->NamePinyin.variants("𠀀"));
        assertThrows(com.whatname.iam.common.exception.BusinessException.class,()->NamePinyin.variants("··"));
    }
}
