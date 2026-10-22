package com.whatname.iam.service.permission;

import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.service.ServiceIdentityMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Consumes each (clientId, nonce) atomically in its own transaction so replay protection survives a
 * later business rollback. The unique constraint uk_client_nonce is the sole arbiter: a duplicate
 * key proves the request was already accepted, and is rejected as INVALID_SERVICE.
 */
@Service
public class NonceStore {
    private final ServiceIdentityMapper mapper;

    public NonceStore(ServiceIdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(String clientId, String nonce, LocalDateTime expiresAt) {
        try {
            mapper.insertNonce(clientId, nonce, expiresAt);
        } catch (DuplicateKeyException replay) {
            throw new BusinessException(INVALID_SERVICE);
        }
    }

    @Scheduled(
            fixedDelayString = "${iam.security.service-auth.nonce-cleanup-delay-ms:300000}",
            initialDelayString = "${iam.security.service-auth.nonce-cleanup-delay-ms:300000}")
    @Transactional
    public void purgeExpired() {
        mapper.deleteExpiredNonces(LocalDateTime.now(ZoneOffset.ofHours(8)));
    }
}
