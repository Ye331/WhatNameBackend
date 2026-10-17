package com.whatname.iam.common.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.whatname.iam.common.exception.BusinessException;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {
    static KeyPair key;static JwtTokenService tokens;static Instant now=Instant.parse("2026-10-16T04:00:00Z");
    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
        tokens=new JwtTokenService(Base64.getEncoder().encodeToString(key.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),"t05-issuer","t05-api","t05-k1",Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void actualRs256RoundTripAndUniqueSessions() {
        var one=tokens.verify(tokens.issue(5,7));var two=tokens.verify(tokens.issue(5,7));
        assertEquals(5,one.userId());assertEquals(7,one.sessionVersion());assertNotEquals(one.jti(),two.jti());
        assertEquals(3600,Duration.between(one.issuedAt(),one.expiresAt()).getSeconds());
    }
    @Test void absentKeysMalformedBearerAndUnsignedTokensFailClosed() {
        var absent=new JwtTokenService("","","","","");
        assertThrows(BusinessException.class,()->absent.issue(5,1));
        assertThrows(BusinessException.class,()->absent.verify("a.b.c"));
        assertThrows(BusinessException.class,()->tokens.verifyBearer(null));
        assertThrows(BusinessException.class,()->tokens.verifyBearer("Basic abc"));
        assertThrows(BusinessException.class,()->tokens.verify("eyJhbGciOiJub25lIn0.e30."));
    }
    @Test void signatureAndHeaderAreActuallyChecked() throws Exception {
        String good=tokens.issue(5,1);String[] pieces=good.split("\\.");
        byte[] signature=Base64.getUrlDecoder().decode(pieces[2]);signature[1]^=1;
        assertThrows(BusinessException.class,()->tokens.verify(pieces[0]+"."+pieces[1]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature)));
        for(JWSHeader h:List.of(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID("t05-k1").build(),
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("other").build())) {
            var token=new SignedJWT(h,JWTClaimsSet.parse(SignedJWT.parse(good).getPayload().toJSONObject()));
            token.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));
            assertThrows(BusinessException.class,()->tokens.verify(token.serialize()));
        }
    }
    @Test void requiredClaimTypesValuesAndLifetimeAreChecked() throws Exception {
        Map<String,Object> original=SignedJWT.parse(tokens.issue(5,1)).getPayload().toJSONObject();
        Map<String,Object> invalid=new LinkedHashMap<>();
        invalid.put("sub",5L);invalid.put("sv","1");invalid.put("iss","other");invalid.put("aud",List.of("other"));
        invalid.put("jti","bad");invalid.put("token_use","refresh");invalid.put("iat",now.plusSeconds(1).getEpochSecond());
        invalid.put("exp",now.getEpochSecond());
        for(var entry:invalid.entrySet()) {
            Map<String,Object> claims=new HashMap<>(original);claims.put(entry.getKey(),entry.getValue());
            String token=sign(claims);assertThrows(BusinessException.class,()->tokens.verify(token),entry.getKey());
        }
        for(String required:List.of("sub","sv","iat","exp","iss","aud","jti","token_use")) {
            Map<String,Object> claims=new HashMap<>(original);claims.remove(required);
            String token=sign(claims);assertThrows(BusinessException.class,()->tokens.verify(token),required);
        }
        Map<String,Object> fractional=new HashMap<>(original);fractional.put("sv",1.5);
        assertThrows(BusinessException.class,()->tokens.verify(sign(fractional)));
    }
    static String sign(Map<String,Object> raw) throws Exception {
        var token=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("t05-k1").build(),new Payload(raw));
        token.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return token.serialize();
    }
}
