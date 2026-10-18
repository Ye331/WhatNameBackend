package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.DataSigningKeys;
import com.whatname.iam.mapper.permission.SignatureMapper;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.whatname.iam.common.api.ErrorCode.*;

/** All operations join the caller's transaction. Verification never repairs a signature. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DataSignatureService {
    private final SignatureMapper mapper;
    private final DataSigningKeys keys;
    public DataSignatureService(SignatureMapper mapper, DataSigningKeys keys) { this.mapper = mapper; this.keys = keys; }

    public Map<String,Object> snapshot(SignedDataType type, long id) {
        var facts = new LinkedHashMap<String,Object>();
        if (type == SignedDataType.ORGANIZATION) {
            if (id != 0) throw new IllegalArgumentException("Organization signature ID is zero");
            facts.put("departments", mapper.organization().stream().map(row -> fields(row, type.fields)).toList());
        } else {
            var row = mapper.record(type, id);
            if (row == null) throw new BusinessException(INTEGRITY_FAILURE);
            facts.putAll(fields(row, type.fields));
            switch (type) {
                case USER_GRANTS -> {
                    facts.put("roles", mapper.userRoles(id));
                    facts.put("sources", mapper.sources(id).stream().map(source -> fields(source,
                            "id,role_id,source_type,source_ref,source_dept_id,status")).toList());
                }
                case ROLE_MENUS -> facts.put("menus", mapper.roleMenus(id));
                case MENU_APIS -> facts.put("apis", mapper.menuApis(id));
                case ADMIN_DELEGABLE_ROLES -> facts.put("roles", mapper.delegableRoles(id));
                case RESOURCE_APPLICATION -> {
                    facts.put("menus", mapper.applicationMenus(id));
                    facts.put("apis", mapper.applicationApis(id));
                }
                default -> { }
            }
        }
        return Map.of("type", type.name(), "id", id, "signVersion", 1, "facts", facts);
    }

    public void sign(SignedDataType type, long id) {
        try {
            String keyId = keys.getKeyId();
            if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) throw new GeneralSecurityException();
            var factory = KeyFactory.getInstance("Ed25519");
            var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.getPrivateKey())));
            var snapshot = snapshot(type, id);
            byte[] data = CanonicalEvidence.bytes(snapshot);
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey); signer.update(data);
            byte[] signature = signer.sign();
            if (!verifyBytes(data, signature, keyId)) throw new GeneralSecurityException("Key pair mismatch");
            mapper.save(type.name(), id, Base64.getEncoder().encodeToString(signature), keyId);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(AUTH_STATE_UNAVAILABLE); }
    }

    /** Safe evidence references, never ciphertext, password hashes, or the private key. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
    public Map<String,Object> verify(SignedDataType type, long id) {
        var record = mapper.signature(type.name(), id);
        return verifySnapshot(type, id, snapshot(type, id), record);
    }

    /** Batch full canonical resource records, signatures and mapping members; no caller projections. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = BusinessException.class)
    public Map<Long,Map<String,Object>> verifyMany(SignedDataType type, Collection<Long> requested) {
        if (!Set.of(SignedDataType.RESOURCE_MENU, SignedDataType.RESOURCE_API, SignedDataType.MENU_APIS).contains(type))
            throw new IllegalArgumentException("Use per-object verification for this type");
        List<Long> ids = requested.stream().distinct().sorted().toList();
        if (ids.isEmpty()) return Map.of();
        var records = new HashMap<Long,Map<String,Object>>();
        var signed = new HashMap<Long,Map<String,Object>>();
        mapper.records(type, ids).forEach(row -> records.put(((Number) row.get("id")).longValue(), row));
        mapper.signatures(type.name(), ids).forEach(row -> signed.put(((Number) row.get("data_id")).longValue(), row));
        var members = new HashMap<Long,List<Long>>();
        if (type == SignedDataType.MENU_APIS) mapper.menuApiMembers(ids).forEach(row -> members
                .computeIfAbsent(((Number) row.get("menu_id")).longValue(), ignored -> new ArrayList<>())
                .add(((Number) row.get("api_id")).longValue()));
        var result = new LinkedHashMap<Long,Map<String,Object>>();
        for (long id : ids) {
            var row = records.get(id);
            if (row == null) throw new BusinessException(INTEGRITY_FAILURE);
            var facts = fields(row, type.fields);
            if (type == SignedDataType.MENU_APIS) facts.put("apis", members.getOrDefault(id, List.of()));
            var snapshot = Map.<String,Object>of("type", type.name(), "id", id, "signVersion", 1, "facts", facts);
            result.put(id, verifySnapshot(type, id, snapshot, signed.get(id)));
        }
        return result;
    }

    private Map<String,Object> verifySnapshot(SignedDataType type, long id, Map<String,Object> snapshot, Map<String,Object> record) {
        try {
            if (record == null || ((Number) record.get("sign_version")).intValue() != 1) throw new GeneralSecurityException();
            byte[] data = CanonicalEvidence.bytes(snapshot);
            String keyId = (String) record.get("key_id");
            if (!verifyBytes(data, Base64.getDecoder().decode((String) record.get("signature")), keyId))
                throw new GeneralSecurityException();
            return Map.of("dataType", type.name(), "dataId", Long.toString(id), "keyId", keyId,
                    "signVersion", 1, "digest", CanonicalEvidence.digest(snapshot), "signature", record.get("signature"));
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(INTEGRITY_FAILURE); }
    }

    /** Detached seal for sanitized historical evidence, independent of today's business rows. */
    public Map<String,Object> seal(Map<String,Object> evidence) {
        try {
            String keyId = keys.getKeyId();
            if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) throw new GeneralSecurityException();
            var key = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.getPrivateKey())));
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key); signer.update(CanonicalEvidence.bytes(evidence));
            byte[] signature = signer.sign();
            if (!verifyBytes(CanonicalEvidence.bytes(evidence), signature, keyId)) throw new GeneralSecurityException();
            return Map.of("keyId", keyId, "signVersion", 1, "signature", Base64.getEncoder().encodeToString(signature));
        } catch (Exception e) { throw new BusinessException(AUDIT_UNAVAILABLE); }
    }

    public boolean verifySeal(Map<String,Object> evidence, Map<String,Object> seal) {
        try {
            return seal != null && "1".equals(String.valueOf(seal.get("signVersion")))
                    && verifyBytes(CanonicalEvidence.bytes(evidence), Base64.getDecoder().decode((String) seal.get("signature")),
                    (String) seal.get("keyId"));
        } catch (Exception e) { return false; }
    }

    private boolean verifyBytes(byte[] data, byte[] signature, String keyId) throws GeneralSecurityException {
        String encoded = keys.getPublicKeys().get(keyId);
        if (encoded == null || encoded.isBlank()) throw new GeneralSecurityException("Untrusted signing key");
        var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key); verifier.update(data); return verifier.verify(signature);
    }

    private Map<String,Object> fields(Map<String,Object> source, String names) {
        var result = new LinkedHashMap<String,Object>();
        for (String name : names.split(",")) result.put(name, source.get(name));
        return result;
    }
}
