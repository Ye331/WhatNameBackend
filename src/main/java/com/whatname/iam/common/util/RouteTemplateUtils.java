package com.whatname.iam.common.util;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class RouteTemplateUtils {
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("^[A-Za-z0-9._~-]+$");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("^\\{[A-Za-z][A-Za-z0-9_]*}$");

    private RouteTemplateUtils() {
    }

    public static String normalizeTemplate(String routeTemplate) {
        if (routeTemplate == null) {
            throw new BusinessException(ErrorCode.INVALID_ROUTE_TEMPLATE);
        }
        String trimmed = routeTemplate.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/") || trimmed.contains("//")) {
            throw new BusinessException(ErrorCode.INVALID_ROUTE_TEMPLATE);
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.substring(1).split("/");
        if (parts.length == 1 && parts[0].isEmpty()) {
            return "/";
        }
        List<String> normalized = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ROUTE_TEMPLATE);
            }
            if (VARIABLE_PATTERN.matcher(part).matches() || SEGMENT_PATTERN.matcher(part).matches()) {
                normalized.add(part);
                continue;
            }
            throw new BusinessException(ErrorCode.INVALID_ROUTE_TEMPLATE);
        }
        return "/" + String.join("/", normalized);
    }

    public static String canonicalTemplate(String normalizedTemplate) {
        String[] parts = normalizedTemplate.substring(1).split("/");
        if (parts.length == 1 && parts[0].isEmpty()) {
            return "/";
        }
        List<String> canonical = new ArrayList<>(parts.length);
        for (String part : parts) {
            canonical.add(VARIABLE_PATTERN.matcher(part).matches() ? "{}" : part);
        }
        return "/" + String.join("/", canonical);
    }
}
