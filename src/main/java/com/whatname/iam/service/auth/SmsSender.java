package com.whatname.iam.service.auth;

/** Replace with an actual provider; failure must throw, never silently accept. */
public interface SmsSender {
    /** Validate provider/channel configuration for every request, including unknown phones. */
    default void requireAvailable() {}
    void send(String normalizedPhone,String purpose,String code);
}
