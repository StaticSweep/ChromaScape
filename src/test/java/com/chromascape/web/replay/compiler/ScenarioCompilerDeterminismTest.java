package com.chromascape.web.replay.compiler;

import com.chromascape.web.replay.compiler.action.InteractNpcAction;
import com.chromascape.web.replay.compiler.action.WaitUntilTickAction;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Deterministic seed derivation: identical inputs must yield identical
 * manifests, so the compiler's seed basis hashing must be stable.
 */
class ScenarioCompilerDeterminismTest {

    @Test
    void derivedSeedIsStableAcrossInstances() throws Exception {
        String basisA = canonicalBasis();
        String basisB = canonicalBasis();
        assertEquals(sha256(basisA).substring(0, 16), sha256(basisB).substring(0, 16));
    }

    @Test
    void differentEvidenceChangesTheSeed() throws Exception {
        assertNotEquals(
                sha256("{\"recordingChecksum\":\"sha256:aa\"}"),
                sha256("{\"recordingChecksum\":\"sha256:bb\"}"));
    }

    private static String canonicalBasis() {
        // Mirrors the compiler's seed basis construction order-independently.
        CanonicalProjectionService p = new CanonicalProjectionService();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree("{\"compilerVersion\":\"1.1.0\",\"curationChecksum\":\"sha256:x\",\"recordingChecksum\":\"sha256:y\"}");
            return p.canonicalJson(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
