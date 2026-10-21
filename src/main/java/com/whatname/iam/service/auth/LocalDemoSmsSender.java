package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Isolated local demonstration channel. It never sends a commercial SMS or logs a proof. Only
 * explicitly configured, pre-existing directories outside Git worktrees are accepted. The business
 * database remains authoritative for expiry, consumption and attempts.
 */
public final class LocalDemoSmsSender implements SmsSender {
    private final String configuredDirectory;
    private final Clock clock;

    public LocalDemoSmsSender(String configuredDirectory) {
        this(configuredDirectory, Clock.systemUTC());
    }

    LocalDemoSmsSender(String configuredDirectory, Clock clock) {
        this.configuredDirectory = configuredDirectory;
        this.clock = clock;
    }

    @Override
    public void requireAvailable() {
        directory();
    }

    @Override
    public void send(String phone, String purpose, String code) {
        Path directory = directory();
        if (phone == null
                || !phone.matches("1[3-9][0-9]{9}")
                || !("SET_PASSWORD".equals(purpose) || "RESET_PASSWORD".equals(purpose))
                || code == null
                || !code.matches("[0-9]{6}")) throw unavailable();
        Path temporary = null;
        try {
            String phoneDigest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(phone.getBytes(StandardCharsets.UTF_8)));
            Path target = directory.resolve(phoneDigest + "-" + purpose + ".sms");
            if (Files.isSymbolicLink(target)) throw unavailable();
            FileAttribute<?>[] attributes =
                    directory.getFileSystem().supportedFileAttributeViews().contains("posix")
                            ? new FileAttribute<?>[] {
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------"))
                            }
                            : new FileAttribute<?>[0];
            temporary = Files.createTempFile(directory, ".sms-", ".tmp", attributes);
            Files.writeString(
                    temporary,
                    "code=" + code + "\nexpiresAt=" + clock.instant().plusSeconds(300) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | NoSuchAlgorithmException | SecurityException exception) {
            throw unavailable();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    /* Never log proof paths or content. */
                }
            }
        }
    }

    private Path directory() {
        try {
            if (configuredDirectory == null || configuredDirectory.isBlank()) throw unavailable();
            Path configured = Path.of(configuredDirectory);
            if (!configured.isAbsolute()) throw unavailable();
            for (Path part : configured)
                if (part.toString().equals(".") || part.toString().equals(".."))
                    throw unavailable();
            Path absolute = configured.toAbsolutePath().normalize();
            for (Path part = absolute; part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part)
                        || Files.exists(part.resolve(".git"), LinkOption.NOFOLLOW_LINKS))
                    throw unavailable();
            }
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(absolute)
                    || !absolute.toRealPath().equals(absolute)) throw unavailable();
            return absolute;
        } catch (IOException | InvalidPathException | SecurityException exception) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SMS_DELIVERY_UNAVAILABLE);
    }
}
