package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.IdUtils;
import com.whatname.iam.dto.grant.BatchDtos.*;

import java.util.*;

/** 规范化输入，最多100人，按用户排序，拒绝重复和不完整目标。 */
record BatchInput(
        String operation, long roleId, List<Target> targets, String reason) {
    static BatchInput parse(String key, Request request) {
        if ((key != null && !key.matches("[!-~]{1,128}"))
                || request == null
                || Boolean.TRUE.equals(request.dryRun())) invalid();
        if (!"GRANT".equals(request.operation()) && !"REVOKE".equals(request.operation()))
            invalid();
        long role = IdUtils.parse(request.roleId());
        text(request.reason(), 512);
        if (request.targets() == null || request.targets().isEmpty() || request.targets().size() > 100) invalid();
        var targets = new TreeMap<Long, Target>();
        for (var t : request.targets()) {
            if (t == null) invalid();
            long user = IdUtils.parse(t.userId());
            if ("GRANT".equals(request.operation())) {
                if (t.grantSourceId() != null) invalid();
                boolean position = "DEPT_POSITION".equals(t.sourceType());
                if (!position && !"MANUAL".equals(t.sourceType())) invalid();
                text(t.sourceRef(), 128);
                if (position != (t.sourceDeptId() != null)) invalid();
                if (position) IdUtils.parse(t.sourceDeptId());
            } else {
                IdUtils.parse(t.grantSourceId());
                if (t.sourceType() != null || t.sourceRef() != null || t.sourceDeptId() != null)
                    invalid();
            }
            var prior = targets.putIfAbsent(user, t);
            if (prior != null) invalid();
        }
        var normalized = List.copyOf(targets.values());
        return new BatchInput(request.operation(), role, normalized, request.reason());
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) invalid();
    }

    private static void invalid() {
        throw new BusinessException(INVALID_ARGUMENT);
    }

}
