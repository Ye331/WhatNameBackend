package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import com.whatname.iam.common.exception.BusinessException;
import java.util.*;

public final class Input {
  private Input() {}

  public static String text(Map<String, Object> body, String key, int max) {
    Object value = body.get(key);
    if (!(value instanceof String s) || s.isBlank() || s.length() > max) fail();
    return ((String) value).strip();
  }

  public static String optional(Map<String, Object> body, String key, int max) {
    return !body.containsKey(key) || body.get(key) == null || "".equals(body.get(key))
        ? null
        : text(body, key, max);
  }

  public static long id(Object value) {
    try {
      if (value == null || !value.toString().matches("[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException();
      return Long.parseLong(value.toString());
    } catch (Exception e) {
      throw new BusinessException(INVALID_ARGUMENT);
    }
  }

  public static long id(Map<String, Object> body, String key) {
    return id(body.get(key));
  }

  public static int number(Map<String, Object> body, String key, int fallback, int... allowed) {
    if (!body.containsKey(key)) return fallback;
    int n;
    try {
      n = Integer.parseInt(body.get(key).toString());
    } catch (Exception e) {
      throw new BusinessException(INVALID_ARGUMENT);
    }
    if (Arrays.stream(allowed).noneMatch(a -> a == n)) fail();
    return n;
  }

  public static List<Long> ids(Map<String, Object> body, String key, int max) {
    if (!(body.get(key) instanceof List<?> list) || list.size() > max)
      throw new BusinessException(INVALID_ARGUMENT);
    return list.stream().map(Input::id).distinct().sorted().toList();
  }

  public static void password(String password) {
    if (password == null
        || password.length() < 8
        || password.length() > 64
        || !password.chars().anyMatch(Character::isLetter)
        || !password.chars().anyMatch(Character::isDigit)) fail();
  }

  public static void fail() {
    throw new BusinessException(INVALID_ARGUMENT);
  }
}
