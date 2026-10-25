package com.whatname.iam.common.security;

import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

import com.whatname.iam.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Separate configured credentials for HR, OA and each external application's auth/check. */
@Component
public class ServiceKeys {
  private final Environment env;

  public ServiceKeys(Environment env) {
    this.env = env;
  }

  public void require(String kind, String supplied) {
    if (!java.util.Set.of("hr", "oa").contains(kind)) throw new BusinessException(INVALID_SERVICE);
    check("iam.integration." + kind + "-key", supplied);
  }

  public void requireApplication(String code, String supplied) {
    if (code == null || !code.matches("[A-Za-z0-9_-]{1,64}"))
      throw new BusinessException(INVALID_SERVICE);
    check("iam.integration.apps." + code + ".key", supplied);
  }

  private void check(String property, String supplied) {
    String expected = env.getProperty(property, "");
    if (expected.length() < 24
        || supplied == null
        || supplied.length() > 256
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
      throw new BusinessException(INVALID_SERVICE);
  }
}
