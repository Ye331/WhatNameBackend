package com.whatname.iam.service.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** UTF-8 JSON, sorted object keys, explicit nulls, decimal strings for all numbers. */
public final class CanonicalEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CanonicalEvidence() { }
    public static byte[] bytes(Object value) {
        try { return JSON.writeValueAsBytes(normalize(value)); }
        catch (Exception e) { throw new IllegalArgumentException("Unsupported evidence", e); }
    }
    public static String digest(Object value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number) return value.toString();
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>();
            map.forEach((key, item) -> sorted.put((String) key, normalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(CanonicalEvidence::normalize).toList();
        throw new IllegalArgumentException("Unsupported evidence value");
    }
    public static String json(Object value) { return new String(bytes(value), StandardCharsets.UTF_8); }
}
