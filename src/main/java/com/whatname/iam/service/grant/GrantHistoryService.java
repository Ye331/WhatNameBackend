package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.mapper.grant.GrantHistoryMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.audit.AuditQueryService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/** 每次请求用新快照统一读取当前范围、留存事实、过滤后总数和响应页。 */
@Service
@Transactional(
        readOnly = true,
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ)
public class GrantHistoryService {
    private final GrantHistoryMapper mapper;
    private final AdminPolicyService policy;
    private final AdminRules rules;
    private final RequestSecurity security;
    private final AuditQueryService audit;

    public GrantHistoryService(
            GrantHistoryMapper mapper,
            AdminPolicyService policy,
            AdminRules rules,
            RequestSecurity security,
            AuditQueryService audit) {
        this.mapper = mapper;
        this.policy = policy;
        this.rules = rules;
        this.security = security;
        this.audit = audit;
    }

    public HistoryPage history(
            String userId, String roleId, String from, String to, int page, int pageSize) {
        Long user = id(userId), role = id(roleId);
        Instant lower = time(from), upper = time(to);
        if (page < 1
                || page > 1_000_000
                || pageSize < 1
                || pageSize > 500
                || (lower != null && upper != null && lower.isAfter(upper)))
            throw new BusinessException(INVALID_ARGUMENT);
        long actor = security.currentUserId();
        var initial = policy.requireBatchReadContext(actor, List.of());
        rules.requireActive(initial, actor);
        security.require(actor, "IAM_GRANTS_VIEW");
        var result = new ArrayList<HistoryItem>();
        long offset = ((long) page - 1) * pageSize, total = 0;
        Long cursor = null;
        while (true) {
            var rows = mapper.candidates(user, cursor, 200);
            if (rows.isEmpty()) break;
            var targets =
                    rows.stream()
                            .map(r -> ((Number) r.get("target_id")).longValue())
                            .distinct()
                            .toList();
            var current = policy.requireBatchReadContext(actor, targets);
            var reader = rules.requireActive(current, actor);
            for (var row : rows) {
                long target = ((Number) row.get("target_id")).longValue();
                var person = current.users().get(target);
                if (reader.adminLevel() != 1
                        && (person == null || !rules.contains(current, reader, person.deptId())))
                    continue;
                // 历史只取已验签的留存证据，不关联当前来源、角色或批量状态来反推。
                var evidence = audit.verifyRetainedEvidence(row);
                for (var change : GrantHistoryEvidence.changes(row, evidence)) {
                    if (role != null && role != change.roleId()) continue;
                    if (lower != null && change.at().isBefore(lower)) continue;
                    if (upper != null && change.at().isAfter(upper)) continue;
                    if (total >= offset && result.size() < pageSize) result.add(change.item());
                    total++;
                }
            }
            cursor = ((Number) rows.getLast().get("id")).longValue();
        }
        return new HistoryPage(List.copyOf(result), page, pageSize, total);
    }

    private static Long id(String value) {
        return value == null ? null : com.whatname.iam.common.util.IdUtils.parse(value);
    }

    private static Instant time(String value) {
        if (value == null) return null;
        try {
            if (!value.matches(
                    "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})"))
                throw new IllegalArgumentException();
            return OffsetDateTime.parse(value).toInstant();
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw new BusinessException(INVALID_ARGUMENT);
        }
    }
}
