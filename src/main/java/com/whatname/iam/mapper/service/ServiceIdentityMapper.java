package com.whatname.iam.mapper.service;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.Map;

/** Trusted-service identity lookup and request-nonce anti-replay store.
 * The identity is read WITHOUT a lock: entry authentication must not hold the service row lock
 * into the business phase (see OaGrantOperations lock order). Nonce consumption is a plain
 * INSERT whose uniqueness is arbitrated by sys_service_nonce.uk_client_nonce. */
@Mapper
public interface ServiceIdentityMapper {
    @Select("SELECT id, app_id, client_id, credential_hash, public_key, key_id, " +
            "allowed_role_ids, allowed_dept_ids, allowed_operations, allowed_scope, status " +
            "FROM sys_service_identity WHERE client_id = #{clientId}")
    Map<String,Object> find(String clientId);

    @Insert("INSERT INTO sys_service_nonce (client_id, nonce, expires_at) " +
            "VALUES (#{clientId}, #{nonce}, #{expiresAt})")
    int insertNonce(@Param("clientId") String clientId, @Param("nonce") String nonce,
                    @Param("expiresAt") LocalDateTime expiresAt);
}
