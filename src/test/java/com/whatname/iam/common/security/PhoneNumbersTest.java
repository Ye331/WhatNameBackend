package com.whatname.iam.common.security;

import com.whatname.iam.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhoneNumbersTest {
    @Test void normalizesWithoutSecrets() {
        assertEquals("13800000005", PhoneNumbers.normalize(" +8613800000005 "));
    }
    @Test void rejectsInvalidNumbers() {
        for (String value : new String[] {"", "138-0000-0005", "23800000005", "1380000000", "138000000005"})
            assertThrows(BusinessException.class, () -> PhoneNumbers.normalize(value));
        assertThrows(BusinessException.class, () -> PhoneNumbers.normalize(null));
    }
}
