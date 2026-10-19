package com.whatname.iam.service.auth;

/** Replaceable channel, including the isolated course-demo sender; failures must throw. */
public interface SmsSender {
    /** Validate provider/channel configuration for every request, including unknown phones. */
    default void requireAvailable() {}
    void send(String normalizedPhone,String purpose,String code);
}
