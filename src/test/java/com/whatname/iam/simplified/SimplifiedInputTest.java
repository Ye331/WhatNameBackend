package com.whatname.iam.simplified;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.PhoneNumbers;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.service.Input;
import com.whatname.iam.service.hr.HrLoginName;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class SimplifiedInputTest {
  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"0", "-1", "1.0", " 1", "01", "9223372036854775808", "abc"})
  void invalidIdsCannotSelectOrMutateResources(String input) {
    assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class, () -> Input.id(input)).errorCode());
  }
  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"12345678", "abcdefgh", "Abc123", " ", "PasswordWithoutDigits"})
  void passwordRequiresLengthLetterAndDigit(String input) {
    assertThrows(BusinessException.class, () -> Input.password(input));
  }
  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"1380000000", "12800000000", "138000000000", "138-0000-0000"})
  void invalidPhonesAreRejected(String input) {
    assertThrows(BusinessException.class, () -> PhoneNumbers.normalize(input));
  }
  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"relative", "/a//b", "/a?x=1", "/a/*", "/{1bad}", "/a b"})
  void invalidApiTemplatesCannotEnterPermissionCatalog(String input) {
    assertEquals(ErrorCode.INVALID_ROUTE_TEMPLATE, assertThrows(BusinessException.class, () -> RouteTemplateUtils.normalizeTemplate(input)).errorCode());
  }
  @ParameterizedTest @ValueSource(strings={"!!!", " ", "😀"})
  void hrNamesWithoutSupportedCharactersAreRejected(String input) {
    assertThrows(BusinessException.class, () -> HrLoginName.initials(input));
  }
  @Test void acceptedInputIsNormalizedWithoutWeakeningLimits() {
    assertEquals(42L, Input.id(Map.of("id", 42), "id"));
    assertEquals(Long.MAX_VALUE, Input.id(Long.MAX_VALUE));
    assertEquals("name", Input.text(Map.of("name", " name "), "name", 8));
    for (Object bad : List.of(" ", "too long", 2)) assertThrows(BusinessException.class, () -> Input.text(Map.of("x", bad), "x", 4));
    assertNull(Input.optional(Map.of(), "x", 4));
    assertNull(Input.optional(Collections.singletonMap("x", null), "x", 4));
    assertNull(Input.optional(Map.of("x", ""), "x", 4));
    assertEquals("yes", Input.optional(Map.of("x", "yes"), "x", 4));
    assertEquals(1, Input.number(Map.of(), "status", 1, 1, 2));
    assertEquals(2, Input.number(Map.of("status", "2"), "status", 1, 1, 2));
    for (Object bad : List.of("abc", 3)) assertThrows(BusinessException.class, () -> Input.number(Map.of("status", bad), "status", 1, 1, 2));
    assertEquals(List.of(1L, 2L), Input.ids(Map.of("ids", List.of(2, 1, 2)), "ids", 3));
    assertThrows(BusinessException.class, () -> Input.ids(Map.of("ids", List.of(1, 2)), "ids", 1));
    assertThrows(BusinessException.class, () -> Input.ids(Map.of("ids", "1"), "ids", 1));
    assertDoesNotThrow(() -> Input.password("Accept123"));
    assertThrows(BusinessException.class, () -> Input.password("A1".repeat(33)));
    assertEquals("13800000000", PhoneNumbers.normalize(" +8613800000000 "));
    assertEquals("zs", HrLoginName.initials("张三"));
    assertEquals("alice01", HrLoginName.initials(" Alice 01 "));
    assertThrows(BusinessException.class, () -> HrLoginName.initials("a".repeat(59)));
  }
  @Test void routeNormalizationAndOverlapPreventAmbiguousApiGrants() {
    assertEquals("/a/{id}", RouteTemplateUtils.normalizeTemplate(" /a/{id}/ "));
    assertEquals("/a/{}", RouteTemplateUtils.canonicalTemplate("/a/{different}"));
    assertEquals("/", RouteTemplateUtils.normalizeTemplate("/"));
    assertEquals("/", RouteTemplateUtils.canonicalTemplate("/"));
    assertTrue(RouteTemplateUtils.overlapsTemplate("/a/{id}", "/a/fixed"));
    assertTrue(RouteTemplateUtils.overlapsTemplate("/a/fixed", "/a/{id}"));
    assertTrue(RouteTemplateUtils.overlapsTemplate("/", "/"));
    assertFalse(RouteTemplateUtils.overlapsTemplate("/a", "/a/b"));
    assertFalse(RouteTemplateUtils.overlapsTemplate("/a", "/b"));
  }
}
