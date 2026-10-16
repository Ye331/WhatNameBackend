package com.whatname.iam.service.grant;

import com.whatname.iam.dto.grant.BatchDtos.*;
import com.whatname.iam.common.exception.BusinessException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

/** 规范化输入，消除请求中目标排列顺序和完全重复目标的影响。 */
record BatchInput(String operation, long roleId, List<Target> targets, String reason, String digest) {
    static BatchInput parse(String key, Request request) {
        if (key == null || !key.matches("[!-~]{1,128}") || request == null || Boolean.TRUE.equals(request.dryRun())) invalid();
        if (!"GRANT".equals(request.operation()) && !"REVOKE".equals(request.operation())) invalid();
        long role = id(request.roleId());
        text(request.reason(), 512);
        if (request.targets() == null || request.targets().isEmpty()) invalid();
        var targets = new TreeMap<Long, Target>();
        for (var t : request.targets()) {
            if (t == null) invalid();
            long user = id(t.userId());
            if ("GRANT".equals(request.operation())) {
                if (t.grantSourceId() != null) invalid();
                boolean position = "DEPT_POSITION".equals(t.sourceType());
                if (!position && !"MANUAL".equals(t.sourceType())) invalid();
                text(t.sourceRef(), 128);
                if (position != (t.sourceDeptId() != null)) invalid();
                if (position) id(t.sourceDeptId());
            } else {
                id(t.grantSourceId());
                if (t.sourceType() != null || t.sourceRef() != null || t.sourceDeptId() != null) invalid();
            }
            var prior = targets.putIfAbsent(user, t);
            if (prior != null && !prior.equals(t)) invalid();
        }
        var normalized = List.copyOf(targets.values());
        return new BatchInput(request.operation(), role, normalized, request.reason(), digest(request, normalized));
    }
    static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw new BusinessException(INVALID_ARGUMENT); }
    }
    private static void text(String value, int maximum) { if (value == null || value.isBlank() || value.length() > maximum) invalid(); }
    private static void invalid() { throw new BusinessException(INVALID_ARGUMENT); }
    private static String digest(Request request, List<Target> targets) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            field(out, request.operation());
            field(out, request.roleId());
            field(out, request.reason());
            out.writeInt(targets.size());
            for (var t : targets) {
                field(out, t.userId());
                field(out, t.sourceType());
                field(out, t.sourceRef());
                field(out, t.sourceDeptId());
                field(out, t.grantSourceId());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot digest batch input", e);
        }
    }
    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
    }
}
