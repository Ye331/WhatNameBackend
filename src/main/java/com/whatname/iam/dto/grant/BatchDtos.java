package com.whatname.iam.dto.grant;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.whatname.iam.dto.admin.AdminDtos.StrictString;

import java.io.IOException;
import java.util.List;

public final class BatchDtos {
    private BatchDtos() {}

    public record Request(
            @JsonDeserialize(using = StrictString.class) String operation,
            @JsonDeserialize(using = StrictString.class) String roleId,
            List<Target> targets,
            @JsonDeserialize(using = StrictString.class) String reason,
            @JsonDeserialize(using = StrictBoolean.class) Boolean dryRun) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown batch field");
        }
    }

    public record Target(
            @JsonDeserialize(using = StrictString.class) String userId,
            @JsonDeserialize(using = StrictString.class) String sourceType,
            @JsonDeserialize(using = StrictString.class) String sourceRef,
            @JsonDeserialize(using = StrictString.class) String sourceDeptId,
            @JsonDeserialize(using = StrictString.class) String grantSourceId) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown target field");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String userId, String status, String grantSourceId, String code) {}

    public record Result(
            String processingStatus,
            int succeeded,
            int failed,
            int total,
            List<Item> items) {}

    public static final class StrictBoolean extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_TRUE
                    && parser.currentToken() != JsonToken.VALUE_FALSE)
                return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            return parser.getBooleanValue();
        }
    }
}
