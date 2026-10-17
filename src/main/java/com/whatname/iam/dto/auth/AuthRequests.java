package com.whatname.iam.dto.auth;

import jakarta.validation.constraints.*;

public final class AuthRequests {
    private AuthRequests() {}
    public enum LoginType { USERNAME, PHONE }
    public enum Purpose { SET_PASSWORD, RESET_PASSWORD }
    public record Login(@NotNull LoginType loginType,@NotBlank @Size(max=64) String identifier,
                         @NotBlank @Size(max=128) String password) {
        @Override public String toString(){return "Login[redacted]";}
    }
    public record SendSms(@NotBlank @Size(max=32) String phone,@NotNull Purpose purpose) {
        @Override public String toString(){return "SendSms[redacted]";}
    }
    public record Password(@NotBlank @Size(max=32) String phone,@NotNull @Pattern(regexp="[0-9]{6}") String code,
                            @NotBlank @Size(max=128) String newPassword) {
        @Override public String toString(){return "Password[redacted]";}
    }
}
