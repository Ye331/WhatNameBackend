package com.whatname.iam.entity.grant;

public final class BatchData {
    private BatchData() {}

    public record Batch(
            Long id,
            Long actorUserId,
            String idempotencyKey,
            String payloadDigest,
            String operation,
            Long roleId,
            String reason,
            String processingStatus,
            Integer totalCount) {}

    public record Item(
            Long id,
            Long batchId,
            Long userId,
            String sourceType,
            String sourceRef,
            Long sourceDeptId,
            Long grantSourceId,
            String processingStatus,
            String resultCode,
            String resultData,
            Integer attempts) {}
}
