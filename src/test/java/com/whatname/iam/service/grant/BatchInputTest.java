package com.whatname.iam.service.grant;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.BatchDtos.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.Stream;

class BatchInputTest {
    private static final Target MANUAL = new Target("1", "MANUAL", "form-1", null, null);

    private static Request request(String operation, List<Target> targets) {
        return new Request(operation, "2", targets, "调整权限", false);
    }

    @Test
    void validatesSmallSynchronousBatchWithoutARequestKey() {
        var second = new Target("3", "MANUAL", "form-2", null, null);
        var input = BatchInput.parse(null, request("GRANT", List.of(second, MANUAL)));
        assertEquals(List.of(MANUAL, second), input.targets());
        assertThrows(BusinessException.class, () -> BatchInput.parse(null, request("GRANT", List.of(MANUAL, MANUAL))));
        var hundred = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new Target(Long.toString(id), "MANUAL", "ref", null, null)).toList();
        assertEquals(100, BatchInput.parse(null, request("GRANT", hundred)).targets().size());
        var tooMany = new ArrayList<>(hundred);
        tooMany.add(new Target("101", "MANUAL", "ref", null, null));
        assertThrows(BusinessException.class, () -> BatchInput.parse(null, request("GRANT", tooMany)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsAmbiguousOrIncompleteRequests(String description, String key, Request input) {
        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                assertThrows(BusinessException.class, () -> BatchInput.parse(key, input))
                        .errorCode());
    }

    static Stream<Arguments> invalidRequests() {
        var valid = request("GRANT", List.of(MANUAL));
        return Stream.of(
                Arguments.of("空幂等键", "", valid),
                Arguments.of("幂等键包含空格", "bad key", valid),
                Arguments.of("幂等键超长", "k".repeat(129), valid),
                Arguments.of("缺少请求", "key", null),
                Arguments.of(
                        "不支持试运行",
                        "key",
                        new Request("GRANT", "2", List.of(MANUAL), "reason", true)),
                Arguments.of("未知操作", "key", request("UPDATE", List.of(MANUAL))),
                Arguments.of("缺少操作", "key", request(null, List.of(MANUAL))),
                Arguments.of("缺少目标列表", "key", request("GRANT", null)),
                Arguments.of("空目标列表", "key", request("GRANT", List.of())),
                Arguments.of("目标为空", "key", request("GRANT", Arrays.asList((Target) null))),
                Arguments.of(
                        "原因缺失", "key", new Request("GRANT", "2", List.of(MANUAL), null, false)),
                Arguments.of(
                        "原因空白", "key", new Request("GRANT", "2", List.of(MANUAL), "  ", false)),
                Arguments.of(
                        "原因超长",
                        "key",
                        new Request("GRANT", "2", List.of(MANUAL), "r".repeat(513), false)),
                Arguments.of(
                        "同一用户来源冲突",
                        "key",
                        request(
                                "GRANT",
                                List.of(
                                        MANUAL,
                                        new Target("1", "MANUAL", "different", null, null)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTargets")
    void rejectsInvalidSourceFields(String description, String operation, Target target) {
        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                assertThrows(
                                BusinessException.class,
                                () -> BatchInput.parse("key", request(operation, List.of(target))))
                        .errorCode());
    }

    static Stream<Arguments> invalidTargets() {
        return Stream.of(
                Arguments.of("授予不能携带回收来源ID", "GRANT", new Target("1", "MANUAL", "ref", null, "3")),
                Arguments.of("未知来源", "GRANT", new Target("1", "OTHER", "ref", null, null)),
                Arguments.of("来源引用缺失", "GRANT", new Target("1", "MANUAL", null, null, null)),
                Arguments.of(
                        "来源引用过长", "GRANT", new Target("1", "MANUAL", "r".repeat(129), null, null)),
                Arguments.of("人工来源不能携带部门", "GRANT", new Target("1", "MANUAL", "ref", "3", null)),
                Arguments.of(
                        "岗位来源必须有部门", "GRANT", new Target("1", "DEPT_POSITION", "ref", null, null)),
                Arguments.of(
                        "岗位部门ID无效", "GRANT", new Target("1", "DEPT_POSITION", "ref", "0", null)),
                Arguments.of("回收必须指定来源ID", "REVOKE", new Target("1", null, null, null, null)),
                Arguments.of("回收不能重新指定来源类型", "REVOKE", new Target("1", "MANUAL", null, null, "3")),
                Arguments.of("回收不能重新指定来源引用", "REVOKE", new Target("1", null, "ref", null, "3")),
                Arguments.of("回收不能重新指定来源部门", "REVOKE", new Target("1", null, null, "2", "3")));
    }
}
