package com.whatname.iam.service.admin;

import com.whatname.iam.service.grant.GrantState;

import java.util.*;

/** 明确列举可留存字段，不将实体、请求或身份密文交给审计序列化器。 */
public final class ChangeEvidence {
    private ChangeEvidence() {}

    public static Map<String, Object> authority(AdminState s) {
        return Map.of(
                "appointments",
                new TreeMap<>(s.appointments())
                        .values().stream()
                                .map(
                                        a ->
                                                fields(
                                                        "userId",
                                                        a.userId(),
                                                        "level",
                                                        a.adminLevel(),
                                                        "deptId",
                                                        a.deptId(),
                                                        "status",
                                                        a.status(),
                                                        "delegableRevision",
                                                        a.delegableRevision(),
                                                        "appointedBy",
                                                        a.appointedBy()))
                                .toList(),
                "departments",
                new TreeMap<>(s.departments())
                        .values().stream()
                                .map(
                                        d ->
                                                fields(
                                                        "id",
                                                        d.id(),
                                                        "parentId",
                                                        d.parentId(),
                                                        "status",
                                                        d.status(),
                                                        "mergedIntoDeptId",
                                                        d.mergedIntoDeptId()))
                                .toList(),
                "users",
                new TreeMap<>(s.users())
                        .values().stream()
                                .map(
                                        u ->
                                                fields(
                                                        "id",
                                                        u.id(),
                                                        "deptId",
                                                        u.deptId(),
                                                        "status",
                                                        u.status()))
                                .toList(),
                "roles",
                new TreeMap<>(s.roles())
                        .values().stream()
                                .map(
                                        r ->
                                                fields(
                                                        "id",
                                                        r.id(),
                                                        "type",
                                                        r.roleType(),
                                                        "status",
                                                        r.status()))
                                .toList(),
                "delegations",
                s.delegations().stream()
                        .sorted(
                                Comparator.comparing(
                                                com.whatname.iam.entity.admin.AdminData.Delegation
                                                        ::adminUserId)
                                        .thenComparing(
                                                com.whatname.iam.entity.admin.AdminData.Delegation
                                                        ::roleId))
                        .map(
                                d ->
                                        fields(
                                                "adminUserId",
                                                d.adminUserId(),
                                                "roleId",
                                                d.roleId(),
                                                "configuredBy",
                                                d.configuredBy()))
                        .toList());
    }

    public static Map<String, Object> grants(GrantState s) {
        return Map.of(
                "userId",
                s.userId(),
                "grantRevision",
                s.grantRevision(),
                "roles",
                s.effectiveRoles().stream().map(r -> r.roleId()).sorted().toList(),
                "sources",
                s.sources().stream()
                        .sorted(
                                Comparator.comparing(
                                        com.whatname.iam.entity.grant.GrantData.Source::id))
                        .map(
                                r ->
                                        fields(
                                                "id",
                                                r.id(),
                                                "userId",
                                                r.userId(),
                                                "roleId",
                                                r.roleId(),
                                                "sourceType",
                                                r.sourceType(),
                                                "sourceRef",
                                                r.sourceRef(),
                                                "sourceDeptId",
                                                r.sourceDeptId(),
                                                "status",
                                                r.status(),
                                                "grantedBy",
                                                r.grantedBy(),
                                                "revokedBy",
                                                r.revokedBy(),
                                                "createdAt",
                                                r.createdAt() == null
                                                        ? null
                                                        : r.createdAt().toString(),
                                                "revokedAt",
                                                r.revokedAt() == null
                                                        ? null
                                                        : r.revokedAt().toString()))
                        .toList());
    }

    private static Map<String, Object> fields(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
