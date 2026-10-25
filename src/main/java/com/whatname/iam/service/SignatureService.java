package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.INTEGRITY_FAILURE;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.DataSigningKeys;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.DomainRepository;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Fixed Ed25519 format; each signature covers the object's outgoing authorization links. */
@Service
public class SignatureService {
  private final DomainRepository db;
  private final DataSigningKeys keys;
  private final ObjectMapper json;

  public SignatureService(DomainRepository db, DataSigningKeys keys, ObjectMapper json) {
    this.db = db;
    this.keys = keys;
    this.json = json;
  }

  public void sign(Row row) {
    try {
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.getPrivateKey()))));
      signer.update(payload(row));
      var entry = entry(row);
      if (entry == null) {
        entry = new DataSignature();
        entry.dataType = type(row);
        entry.dataId = row.id;
      }
      entry.signature = Base64.getEncoder().encodeToString(signer.sign());
      db.save(entry);
    } catch (Exception e) {
      throw new BusinessException(INTEGRITY_FAILURE, e);
    }
  }

  public void verify(Row row) {
    try {
      var entry = entry(row);
      if (entry == null) throw new GeneralSecurityException();
      var verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(
          KeyFactory.getInstance("Ed25519")
              .generatePublic(
                  new X509EncodedKeySpec(
                      Base64.getDecoder().decode(keys.getPublicKeys().get(keys.getKeyId())))));
      verifier.update(payload(row));
      if (!verifier.verify(Base64.getDecoder().decode(entry.signature)))
        throw new GeneralSecurityException();
    } catch (Exception e) {
      throw new BusinessException(INTEGRITY_FAILURE, e);
    }
  }

  private DataSignature entry(Row row) {
    return db
        .query(
            DataSignature.class,
            "from DataSignature where dataType=?1 and dataId=?2",
            type(row),
            row.id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private String type(Row row) {
    return row.getClass().getSimpleName();
  }

  private byte[] payload(Row row) throws Exception {
    Object data;
    if (row instanceof Account u)
      data =
          Arrays.asList(
              u.username,
              u.realName,
              u.phone,
              u.passwordHash,
              u.employeeNo,
              u.deptId,
              u.status,
              u.source,
              u.employmentType,
              u.managementScope,
              u.sessionVersion,
              db.roles(u.id));
    else if (row instanceof Role r)
      data = Arrays.asList(r.roleCode, r.roleName, r.roleDesc, r.status, db.menus(r.id));
    else if (row instanceof Menu m)
      data =
          Arrays.asList(
              m.appId,
              m.parentId,
              m.menuCode,
              m.menuName,
              m.menuType,
              m.path,
              m.sortOrder,
              m.status,
              db.apis(m.id));
    else if (row instanceof Api a)
      data =
          Arrays.asList(
              a.appId, a.apiCode, a.apiName, a.apiPath, a.canonicalPath, a.httpMethod, a.status);
    else if (row instanceof Application a)
      data = Arrays.asList(a.appCode, a.appName, a.appDesc, a.status);
    else if (row instanceof Department d)
      data = Arrays.asList(d.parentId, d.deptCode, d.deptName, d.status, d.mergedIntoDeptId);
    else throw new IllegalArgumentException();
    return json.writeValueAsBytes(Arrays.asList("coursework-v1", type(row), row.id, data));
  }
}
