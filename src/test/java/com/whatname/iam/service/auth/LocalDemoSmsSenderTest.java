package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class LocalDemoSmsSenderTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC);
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(SmsChannelConfiguration.class);

    @Test void ordinaryConfigurationRefusesEvenWhenDirectoryIsConfigured() {
        contexts.withPropertyValues("iam.sms.demo-directory=" + directory).run(context -> {
            SmsSender sender = context.getBean(SmsSender.class);
            unavailable(sender::requireAvailable);
            unavailable(() -> sender.send("13800000000", "SET_PASSWORD", "123456"));
        });
    }

    @Test void explicitDemoProfileSelectsTheExistingReplaceablePort() {
        contexts.withInitializer(context -> context.getEnvironment().setActiveProfiles("sms-demo"))
                .withPropertyValues("iam.sms.demo-directory=" + directory).run(context -> {
                    assertEquals(1, context.getBeansOfType(SmsSender.class).size());
                    assertInstanceOf(LocalDemoSmsSender.class, context.getBean(SmsSender.class));
                    context.getBean(SmsSender.class).requireAvailable();
                });
    }

    @Test void explicitProviderOverridesDemoConfiguration() {
        SmsSender supplied = (phone, purpose, code) -> { throw new IllegalStateException("provider fixture"); };
        contexts.withInitializer(context -> context.getEnvironment().setActiveProfiles("sms-demo"))
                .withBean(SmsSender.class, () -> supplied).run(context -> {
                    assertEquals(1, context.getBeansOfType(SmsSender.class).size());
                    assertSame(supplied, context.getBean(SmsSender.class));
                });
    }

    @Test void missingOrInvalidDirectoryNeverSilentlyAcceptsDelivery() throws Exception {
        for (String value : new String[]{"", "relative-folder", directory.resolve("missing").toString(),
                directory.resolve("..").resolve(directory.getFileName()).toString()})
            unavailable(new LocalDemoSmsSender(value)::requireAvailable);
        Path ordinaryFile = Files.writeString(directory.resolve("file"), "");
        unavailable(new LocalDemoSmsSender(ordinaryFile.toString())::requireAvailable);
        contexts.withInitializer(context -> context.getEnvironment().setActiveProfiles("sms-demo"))
                .run(context -> unavailable(context.getBean(SmsSender.class)::requireAvailable));
    }

    @Test void gitWorktreesAreNotAllowedToStoreDemoProofs() throws Exception {
        Files.createDirectory(directory.resolve(".git"));
        Path inbox = Files.createDirectory(directory.resolve("inbox"));
        unavailable(new LocalDemoSmsSender(inbox.toString())::requireAvailable);
        assertEquals(0, fileCount(inbox));
    }

    @Test void separatePhoneAndPurposeFilesContainOnlyCodeAndExpiryAndReplaceOldProof() throws Exception {
        var sender = new LocalDemoSmsSender(directory.toString(), clock);
        sender.send("13800000000", "SET_PASSWORD", "123456");
        sender.send("13800000000", "RESET_PASSWORD", "654321");
        sender.send("13900000000", "SET_PASSWORD", "654322");
        sender.send("13800000000", "SET_PASSWORD", "123457");
        assertEquals(3, fileCount(directory));
        assertEquals("code=123457\nexpiresAt=2026-10-18T08:05:00Z\n", Files.readString(proof("13800000000", "SET_PASSWORD")));
        assertTrue(Files.readString(proof("13800000000", "RESET_PASSWORD")).startsWith("code=654321\n"));
        assertTrue(Files.readString(proof("13900000000", "SET_PASSWORD")).startsWith("code=654322\n"));
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                assertFalse(file.getFileName().toString().contains("13800000000"));
                assertFalse(Files.readString(file).contains("13800000000"));
            }
        }
    }

    @Test void pathAndPayloadInjectionCannotCreateAnOutOfChannelFile() throws Exception {
        var sender = new LocalDemoSmsSender(directory.toString(), clock);
        unavailable(() -> sender.send("../13800000000", "SET_PASSWORD", "123456"));
        unavailable(() -> sender.send("13800000000", "../outside", "123456"));
        unavailable(() -> sender.send("13800000000", "SET_PASSWORD", "123456\nsecret=x"));
        unavailable(() -> sender.send("13800000000", null, "123456"));
        assertEquals(0, fileCount(directory));
    }

    @Test void deliveryIoFailurePropagatesAndLeavesNoTemporaryProof() throws Exception {
        Files.createDirectory(proof("13800000000", "SET_PASSWORD"));
        var sender = new LocalDemoSmsSender(directory.toString(), clock);
        unavailable(() -> sender.send("13800000000", "SET_PASSWORD", "123456"));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }

    private Path proof(String phone, String purpose) throws Exception {
        return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(phone.getBytes(StandardCharsets.UTF_8))) + "-" + purpose + ".sms");
    }

    private static long fileCount(Path directory) throws Exception {
        try (var files = Files.list(directory)) { return files.count(); }
    }

    private static void unavailable(Runnable call) {
        assertEquals(ErrorCode.SMS_DELIVERY_UNAVAILABLE, assertThrows(BusinessException.class, call::run).errorCode());
    }
}
