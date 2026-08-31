package com.chromascape.web.replay.capability;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Performs the xRSPS capability handshake by launching
 * {@code node <entrypoint> --capabilities}, parsing the manifest,
 * verifying configured expected versions, and updating
 * {@link ReplayCapabilityService}.
 *
 * Runs at startup and periodically so preflight reflects reality.
 */
@Component
@ConditionalOnProperty(prefix = "replay", name = "enabled", havingValue = "true")
public class XrspsStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(XrspsStartupValidator.class);
    private static final java.util.Set<String> SUPPORTED_FIDELITIES =
            java.util.Set.of("SYNTHETIC_CONTRACT", "XRSPS_AUTHORITATIVE");
    private static final java.util.Set<String> SUPPORTED_ACTIONS = java.util.Set.of(
            "WAIT_UNTIL_TICK", "WALK_TO", "INTERACT_NPC", "INTERACT_OBJECT",
            "INTERACT_GROUND_ITEM", "INTERACT_INVENTORY_ITEM", "INTERACT_WIDGET",
            "SELECT_DIALOGUE_OPTION", "CAST_SPELL", "EQUIP_ITEM");
    private static final List<Integer> SUPPORTED_SCENARIO_VERSIONS = List.of(1, 2);
    private static final String SUPPORTED_VIEWER_TRACE_SCHEMA = "xrsps-replay-trace/v1";

    private final ReplayProperties properties;
    private final ReplayCapabilityService capabilityService;
    private final ObjectMapper mapper;

    public XrspsStartupValidator(ReplayProperties properties,
                                 ReplayCapabilityService capabilityService,
                                 ObjectMapper mapper) {
        this.properties = properties;
        this.capabilityService = capabilityService;
        this.mapper = mapper;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "#{T(java.time.Duration).ofSeconds(60).toMillis()}")
    public void refreshCapabilities() {
        ReplayProperties.Xrsps cfg = properties.getXrsps();
        if (cfg.getEntrypoint() == null || cfg.getEntrypoint().isBlank()) {
            capabilityService.markUnavailable("xRSPS entrypoint is not configured");
            return;
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(cfg.getExecutable());
            command.add(cfg.getEntrypoint());
            command.add("--capabilities");

            ProcessBuilder pb = new ProcessBuilder(command);
            if (cfg.getWorkingDirectory() != null && !cfg.getWorkingDirectory().isBlank()) {
                pb.directory(new File(cfg.getWorkingDirectory()));
            }
            pb.redirectErrorStream(false);
            pb.environment().clear();
            pb.environment().put("PATH", System.getenv("PATH") != null ? System.getenv("PATH") : "/usr/bin:/bin");
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) {
                pb.environment().put("SystemRoot", systemRoot);
            }

            Process process = pb.start();
            String stdoutLine;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                stdoutLine = reader.readLine();
            }
            boolean exited = process.waitFor(cfg.getStartupTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                capabilityService.markUnavailable("capability handshake timed out");
                return;
            }
            if (process.exitValue() != 0 || stdoutLine == null || stdoutLine.isBlank()) {
                capabilityService.markUnavailable("capability handshake exited with code " + process.exitValue());
                return;
            }

            JsonNode node = mapper.readTree(stdoutLine);
            CapabilityManifest manifest = parseManifest(node);

            List<String> versionProblems = verifyVersions(manifest, cfg);
            if (!versionProblems.isEmpty()) {
                capabilityService.markUnavailable(String.join("; ", versionProblems));
                return;
            }
            capabilityService.updateFromHandshake(manifest);
        } catch (Exception e) {
            log.warn("xRSPS capability handshake failed: {}", e.getMessage());
            capabilityService.markUnavailable("handshake error: " + e.getClass().getSimpleName());
        }
    }

    CapabilityManifest parseManifest(JsonNode node) {
        CapabilityManifest.CapabilityModes modes = new CapabilityManifest.CapabilityModes(
                node.path("modes").path("observedPlayback").asBoolean(false),
                node.path("modes").path("policyReplay").asBoolean(false),
                node.path("modes").path("causalSimulation").asBoolean(false));
        JsonNode xrspsNode = node.path("xrsps");
        CapabilityManifest.XrspsIdentity identity = new CapabilityManifest.XrspsIdentity(
                xrspsNode.path("adapterVersion").asText(""),
                xrspsNode.path("buildCommit").asText(""),
                xrspsNode.path("cacheRevision").asText(""),
                xrspsNode.path("contentRevision").asText(""));
            return new CapabilityManifest(
                    node.path("schemaVersion").asInt(-1),
                    node.path("controlAllowed").asBoolean(false),
                modes,
                intList(node.path("scenarioSchemaVersions")),
                intList(node.path("semanticActionVersions")),
                intList(node.path("snapshotVersions")),
                intList(node.path("projectionVersions")),
                stringList(node.path("hashVersions")),
                identity,
                    node.path("openRuneAvailable").asBoolean(false),
                node.path("certifiable").asBoolean(false),
                node.path("fidelity").asText("SYNTHETIC_CONTRACT"),
                stringList(node.path("supportedActions")),
                node.path("viewerTraceSchema").asText(null));
    }

    List<String> verifyVersions(CapabilityManifest manifest, ReplayProperties.Xrsps cfg) {
        List<String> problems = new ArrayList<>();
        if (manifest.schemaVersion() != 1) {
            problems.add("unsupported capability schemaVersion " + manifest.schemaVersion());
        }
        if (manifest.controlAllowed()) {
            problems.add("controlAllowed must be false");
        }
        if (manifest.openRuneAvailable()) {
            problems.add("openRuneAvailable must be false");
        }
        if (!SUPPORTED_SCENARIO_VERSIONS.equals(manifest.scenarioSchemaVersions())) {
            problems.add("unsupported scenarioSchemaVersions " + manifest.scenarioSchemaVersions());
        }
        if (!SUPPORTED_VIEWER_TRACE_SCHEMA.equals(manifest.viewerTraceSchema())) {
            problems.add("unsupported viewerTraceSchema " + manifest.viewerTraceSchema());
        }
        if (!SUPPORTED_FIDELITIES.contains(manifest.fidelity())) {
            problems.add("unsupported fidelity " + manifest.fidelity());
        }
        if (manifest.supportedActions().stream().anyMatch(action -> !SUPPORTED_ACTIONS.contains(action))) {
            problems.add("unsupported semantic action declaration");
        }
        if (manifest.modes().causalSimulation() && manifest.supportedActions().isEmpty()) {
            problems.add("causal simulation declares no supported actions");
        }
        requireExpected(problems, "adapterVersion",
                cfg.getExpectedAdapterVersion(), manifest.xrsps().adapterVersion());
        requireExpected(problems, "buildCommit",
                cfg.getExpectedBuildCommit(), manifest.xrsps().buildCommit());
        requireExpected(problems, "cacheRevision",
                cfg.getExpectedCacheRevision(), manifest.xrsps().cacheRevision());
        requireExpected(problems, "contentRevision",
                cfg.getExpectedContentRevision(), manifest.xrsps().contentRevision());
        return problems;
    }

    private void requireExpected(List<String> problems, String name, String expected, String actual) {
        if (expected != null && !expected.isBlank() && !expected.equals(actual)) {
            problems.add(name + " mismatch: expected " + expected + ", got " + actual);
        }
    }

    private static List<Integer> intList(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode n : array) {
                out.add(n.asInt());
            }
        }
        return out;
    }

    private static List<String> stringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode n : array) {
                out.add(n.asText());
            }
        }
        return out;
    }
}
