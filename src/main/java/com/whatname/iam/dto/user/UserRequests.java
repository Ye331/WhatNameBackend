package com.whatname.iam.dto.user;
import jakarta.validation.constraints.*;

public final class UserRequests {
    private UserRequests() {}
    public record Temporary(@NotBlank @Size(max=64) String realName,@NotBlank @Size(max=32) String phone,
                             @NotNull @Pattern(regexp="[1-9][0-9]{0,18}") String deptId,
                             @NotBlank @Size(max=512) String reason) {
        @Override public String toString(){return "Temporary[redacted]";}
    }
    public record Status(@NotNull @Min(1) @Max(2) Integer status,@NotBlank @Size(max=512) String reason) {}
}
