package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Per-instance coarse IP admission guard, in addition to durable per-account/code limits. */
@Component
public class PublicRequestLimiter {
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock = Clock.systemUTC();

    public synchronized void check(String operation, String remoteAddress, int maximum) {
        long minute = clock.instant().getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute < minute);
        String key = operation + ":" + remoteAddress;
        if (!windows.containsKey(key) && windows.size() >= 10000)
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        Window w = windows.computeIfAbsent(key, k -> new Window(minute));
        if (++w.count > maximum) throw new BusinessException(ErrorCode.RATE_LIMITED);
    }

    private static class Window {
        final long minute;
        int count;

        Window(long minute) {
            this.minute = minute;
        }
    }
}
