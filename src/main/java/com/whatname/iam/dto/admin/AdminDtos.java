package com.whatname.iam.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;

public final class AdminDtos {
    private AdminDtos() {}
    public static final String ID = "[1-9][0-9]{0,18}";

    public record AppointmentRequest(@NotNull @Pattern(regexp = ID) @JsonDeserialize(using = StrictString.class) String userId,
            @Min(1) @Max(65535) @JsonDeserialize(using = StrictInteger.class) int adminLevel,
            @NotNull @Pattern(regexp = ID) @JsonDeserialize(using = StrictString.class) String deptId,
            @NotBlank @Size(max = 512) @JsonDeserialize(using = StrictString.class) String reason) {}
    public record RevokeRequest(@NotBlank @Size(max = 512) @JsonDeserialize(using = StrictString.class) String reason) {}
    public record DelegationRequest(@NotNull @JsonDeserialize(contentUsing = StrictString.class)
            List<@NotNull @Pattern(regexp = ID) String> roleIds,
            @NotNull @Pattern(regexp = "v[1-9][0-9]{0,9}") @JsonDeserialize(using = StrictString.class) String expectedVersion,
            @NotBlank @Size(max = 512) @JsonDeserialize(using = StrictString.class) String reason) {}
    public record Summary(String userId, int adminLevel, String deptId, int status) {}
    public record Revocation(String userId, int status, boolean appointmentRevoked) {}
    public record DelegableRoles(String userId, List<String> roleIds, String delegationVersion) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(boolean appointed, Integer adminLevel, String deptId,
                        List<String> scopeRootIds, String delegableRolesVersion) {}

    /** 严格遵守契约中的 JSON 类型，不修改其他模块的 ObjectMapper 配置。 */
    public static final class StrictString extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) return context.reportInputMismatch(String.class, "Expected a JSON string");
            return parser.getText();
        }
    }
    public static final class StrictInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return context.reportInputMismatch(Integer.class, "Expected a JSON integer");
            return parser.getIntValue();
        }
    }
}
