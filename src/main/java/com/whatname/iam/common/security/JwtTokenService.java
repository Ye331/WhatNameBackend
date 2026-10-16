package com.whatname.iam.common.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** RS256 only, local keys only; no caller-selected algorithms, URLs or key sources. */
@Service
public class JwtTokenService {
    private final String privateEncoded, publicEncoded, issuer, audience, keyId;
    private final Clock clock;

    @Autowired
    public JwtTokenService(@Value("${iam.security.jwt-private-key:}") String privateKey,
                           @Value("${iam.security.jwt-public-key:}") String publicKey,
                           @Value("${iam.security.jwt-issuer:}") String issuer,
                           @Value("${iam.security.jwt-audience:}") String audience,
                           @Value("${iam.security.jwt-key-id:}") String keyId) {
        this(privateKey, publicKey, issuer, audience, keyId, Clock.systemUTC());
    }

    public JwtTokenService(String privateKey, String publicKey, String issuer, String audience, String keyId, Clock clock) {
        this.privateEncoded=privateKey; this.publicEncoded=publicKey; this.issuer=issuer;
        this.audience=audience; this.keyId=keyId; this.clock=clock;
    }

    public String issue(long userId, long sessionVersion) {
        try {
            requireSettings();
            if (userId<=0 || sessionVersion<=0) throw new IllegalArgumentException();
            RSAPrivateKey privateKey=(RSAPrivateKey)KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateEncoded)));
            if (privateKey.getModulus().bitLength()<2048 || !privateKey.getModulus().equals(publicKey().getModulus()))
                throw new IllegalArgumentException();
            Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var claims=new JWTClaimsSet.Builder().subject(Long.toString(userId)).issuer(issuer).audience(audience)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600)))
                    .jwtID(UUID.randomUUID().toString()).claim("sv",sessionVersion).claim("token_use","access").build();
            var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(keyId).build(),claims);
            token.sign(new RSASSASigner(privateKey));
            return token.serialize();
        } catch (Exception e) { throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
    }

    public SessionPrincipal verifyBearer(String authorization) {
        if (authorization==null || !authorization.startsWith("Bearer ") || authorization.length()>8192)
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        return verify(authorization.substring(7));
    }

    public SessionPrincipal verify(String encoded) {
        // Missing verifier is an operational fault, never acceptance of an unsigned token.
        RSAPublicKey key=publicKey();
        try {
            requireSettings();
            if (encoded==null || encoded.length()>8192) throw new IllegalArgumentException();
            var jwt=SignedJWT.parse(encoded);
            var h=jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(h.getAlgorithm()) || !JOSEObjectType.JWT.equals(h.getType())
                    || !keyId.equals(h.getKeyID()) || (h.getCriticalParams()!=null && !h.getCriticalParams().isEmpty())
                    || !jwt.verify(new RSASSAVerifier(key))) throw new IllegalArgumentException();
            Map<String,Object> raw=jwt.getPayload().toJSONObject();
            if (raw==null || !(raw.get("sub") instanceof String sub) || !sub.matches("[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException();
            long userId=Long.parseLong(sub), sv=integer(raw.get("sv"));
            long iat=integer(raw.get("iat")), exp=integer(raw.get("exp")), now=clock.instant().getEpochSecond();
            if (sv<=0 || iat>now || exp<=now || exp-iat!=3600 || !issuer.equals(raw.get("iss"))
                    || !(audience.equals(raw.get("aud")) || List.of(audience).equals(raw.get("aud"))) || !"access".equals(raw.get("token_use"))
                    || !(raw.get("jti") instanceof String jti) || !jti.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                throw new IllegalArgumentException();
            return new SessionPrincipal(userId,sv,jti,Instant.ofEpochSecond(iat),Instant.ofEpochSecond(exp));
        } catch (Exception e) { throw new BusinessException(ErrorCode.INVALID_TOKEN); }
    }

    private static long integer(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) throw new IllegalArgumentException();
        return ((Number)value).longValue();
    }
    private void requireSettings() {
        if (issuer.isBlank() || audience.isBlank() || keyId.isBlank()) throw new IllegalArgumentException();
    }
    private RSAPublicKey publicKey() {
        try {
            requireSettings();
            var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicEncoded)));
            if (key.getModulus().bitLength()<2048) throw new IllegalArgumentException();
            return key;
        } catch (Exception e) { throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
    }
    public record SessionPrincipal(long userId,long sessionVersion,String jti,Instant issuedAt,Instant expiresAt) {
        @Override public String toString() { return "SessionPrincipal[redacted]"; }
    }
}
