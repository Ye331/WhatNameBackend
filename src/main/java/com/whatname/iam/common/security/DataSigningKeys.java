package com.whatname.iam.common.security;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** PKCS8 private key and X509 public keys, Base64 encoded; no generated or default keys. */
@Component
@ConfigurationProperties(prefix = "iam.security.data-signing")
public class DataSigningKeys {
    private String keyId = "", privateKey = "";
    private Map<String,String> publicKeys = new HashMap<>();
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { keyId = value; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String value) { privateKey = value; }
    public Map<String,String> getPublicKeys() { return publicKeys; }
    public void setPublicKeys(Map<String,String> value) { publicKeys = value; }
}
