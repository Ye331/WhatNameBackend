package com.whatname.iam.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DataSigningKeysTest {
    @TempDir Path directory;

    private DataSigningKeys local() {
        var keys = new DataSigningKeys();
        keys.setLocalAutoGenerate(true);
        keys.setLocalKeyFile(directory.resolve("signing.properties").toString());
        return keys;
    }

    @Test void createsOnceAndReusesAcrossInstances() throws Exception {
        var first = local(); first.initialize();
        var saved = Files.readAllBytes(directory.resolve("signing.properties"));
        var second = local(); second.initialize();
        assertEquals(first.getPrivateKey(), second.getPrivateKey());
        assertEquals(first.getPublicKeys(), second.getPublicKeys());
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("signing.properties")));
        assertEquals(1, first.getPublicKeys().size());
    }

    @Test void corruptFileIsRejectedWithoutRegeneration() throws Exception {
        var file = directory.resolve("signing.properties");
        Files.writeString(file, "broken");
        assertThrows(IllegalStateException.class, () -> local().initialize());
        assertEquals("broken", Files.readString(file));
    }

    @Test void incompleteExplicitKeysAreNotSilentlyReplaced() {
        var keys = local(); keys.setPrivateKey("invalid");
        assertThrows(IllegalStateException.class, keys::initialize);
        assertFalse(Files.exists(directory.resolve("signing.properties")));
    }

    @Test void explicitValidPairTakesPriorityOverLocalFile() throws Exception {
        var first = local(); first.initialize();
        var explicit = local();
        explicit.setKeyId(first.getKeyId());
        explicit.setPrivateKey(first.getPrivateKey());
        explicit.setPublicKeys(first.getPublicKeys());
        explicit.setLocalKeyFile(directory.resolve("unused.properties").toString());
        explicit.initialize();
        assertFalse(Files.exists(directory.resolve("unused.properties")));
    }

    @Test void autoGenerationMustBeEnabled() {
        var keys = new DataSigningKeys();
        keys.setLocalKeyFile(directory.resolve("unused.properties").toString());
        keys.initialize();
        assertFalse(Files.exists(directory.resolve("unused.properties")));
    }
}
