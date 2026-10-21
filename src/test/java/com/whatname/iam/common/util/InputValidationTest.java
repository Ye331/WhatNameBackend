package com.whatname.iam.common.util;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.resource.ResourceRequests.*;
import com.whatname.iam.dto.role.RoleRequests.UpdateRoleRequest;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InputValidationTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0", "-1", "01", " 1", "1.0", "9223372036854775808"})
    void invalidIdsHaveTheSameBusinessError(String value) {
        assertEquals(
                INVALID_ARGUMENT,
                assertThrows(BusinessException.class, () -> IdUtils.parse(value)).errorCode());
    }

    @Test
    void largestIdIsAcceptedWithoutLosingPrecision() {
        assertEquals(Long.MAX_VALUE, IdUtils.parse("9223372036854775807"));
    }

    @Test
    void overflowingRevisionIsAnInputError() {
        assertEquals(
                INVALID_ARGUMENT,
                assertThrows(
                                BusinessException.class,
                                () -> RevisionUtils.parseExpectedVersion("v2147483648"))
                        .errorCode());
    }

    @Test
    void everyResourcePatchRequiresAnActualChange() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var app = new UpdateApplicationRequest();
            app.setReason("test");
            var menu = new UpdateMenuRequest();
            menu.setReason("test");
            var api = new UpdateApiRequest();
            api.setReason("test");
            var role = new UpdateRoleRequest();
            role.setReason("test");
            for (Object request : new Object[] {app, menu, api, role}) {
                assertTrue(
                        validator.validate(request).stream()
                                .anyMatch(
                                        error ->
                                                error.getPropertyPath()
                                                        .toString()
                                                        .equals("changePresent")));
            }
            app.setStatus(0);
            menu.setStatus(0);
            api.setStatus(0);
            role.setStatus(0);
            for (Object request : new Object[] {app, menu, api, role}) {
                assertTrue(validator.validate(request).isEmpty());
            }
        }
    }

    @Test
    void idListsPreserveOrderAndRejectInvalidMembers() {
        assertEquals(java.util.List.of(3L, 1L), IdUtils.distinct(java.util.List.of(3L, 1L, 3L)));
        assertEquals(java.util.List.of(), IdUtils.distinct(null));
        for (Long invalid : java.util.Arrays.asList(null, 0L, -1L)) {
            assertEquals(
                    INVALID_ARGUMENT,
                    assertThrows(
                                    BusinessException.class,
                                    () -> IdUtils.distinct(java.util.Arrays.asList(1L, invalid)))
                            .errorCode());
        }
    }
}
