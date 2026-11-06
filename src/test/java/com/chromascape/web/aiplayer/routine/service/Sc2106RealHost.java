package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.config.Sc2106ExecutionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The real Micro6 host and XRSPS worker for the SC-2106 integration tests. ChromaScape signs a
 * candidate over the real Micro6 shaded JAR and builds the execution closure from the real closure
 * JARs with {@link CandidateExecutionClosureV1}; jobs then run through the real {@code
 * CandidateHostMain} process started from those same closure JARs.
 *
 * <p>The signing key is generated per run, never a published test key, so the host's production
 * trust-policy loader accepts it. The worker is a development build, so runs pass but are not
 * certifiable. Missing inputs fail; the tasks that use this refuse skips.
 */
final class Sc2106RealHost {
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final String HOST_MAIN =
      "net.runelite.client.plugins.microbot.accountbuilder.sharedcore.candidate.CandidateHostMain";
  static final String JOURNAL =
      "net.runelite.client.plugins.microbot.accountbuilder.sharedcore.journal.SegmentedAttemptJournal";
  private static final String NORMALIZER =
      "net.runelite.client.plugins.microbot.accountbuilder.sharedcore.fishing.FishingTaskDefinitionNormalizer";
  static final String POLICY_VERSION = "sc814-trust-policy/1";
  static final String PROPOSAL = "proposal-e2e";

  static Path closureDir;
  static Path xrspsPackage;
  static JsonNode inputs;
  static JsonNode vectors;
  static Path bundle;
  static String jarName;
  static String sbomName;
  static String keyId;
  static KeyPair keys;

  private Sc2106RealHost() {}

  /** Signs the candidate and writes its bundle under {@code temp}. */
  static void prepare(Path temp) throws Exception {
    Path micro6Build = Path.of(required("sc2106.micro6.build"));
    xrspsPackage = Path.of(required("sc2106.xrsps.package"));
    closureDir = micro6Build.resolve("sc2106/execution-closure");
    assertTrue(
        Files.isRegularFile(closureDir.resolve("execution-closure-inputs.json")),
        "run :client:sc2106ExecutionClosureInputs in Micro6 first: " + closureDir);
    assertTrue(
        Files.isRegularFile(xrspsPackage.resolve("dist/worker/main.js")),
        "the XRSPS worker must be built: " + xrspsPackage);
    inputs =
        MAPPER.readTree(Files.readAllBytes(closureDir.resolve("execution-closure-inputs.json")));
    vectors = MAPPER.readTree(Files.readAllBytes(Path.of(required("sc967.vectors.path"))));
    Path shaded;
    try (var libs = Files.list(micro6Build.resolve("libs"))) {
      shaded =
          libs.filter(p -> p.getFileName().toString().endsWith("-shaded.jar"))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("no shaded JAR under " + micro6Build.resolve("libs")));
    }
    byte[] signedJar = Files.readAllBytes(shaded);

    keyId = vectors.path("key_id").asText();
    keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Path privateKey = temp.resolve("producer.pem");
    Files.writeString(
        privateKey,
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
    CandidateSigner signer = new CandidateSigner(keyId, privateKey);

    byte[] sbom = artifact("sbom");
    ObjectNode certification = (ObjectNode) MAPPER.readTree(artifact("certification"));
    certification.put("jar_sha256", sha(signedJar));
    byte[] certificationBytes =
        Sc967CanonicalJson.canonical(certification).getBytes(StandardCharsets.UTF_8);
    ObjectNode manifest = null;
    for (JsonNode vector : vectors.path("vectors")) {
      if ("valid-canonical-manifest".equals(vector.path("name").asText())) {
        manifest = (ObjectNode) vector.path("manifest").deepCopy();
      }
    }
    jarName = manifest.path("jar_name").asText();
    sbomName = manifest.path("sbom_name").asText();
    manifest.put("jar_sha256", sha(signedJar));
    manifest.put("jar_size", (long) signedJar.length);
    manifest.put("certification_document_hash", sha(certificationBytes));
    ObjectNode candidate = manifest.with("candidate");
    candidate.removeAll();
    candidate.put("certification_hash", sha(certificationBytes));
    String candidateHash = signer.unsignedHash(manifest);
    candidate.put("candidate_hash", candidateHash);
    candidate.put("candidate_id", "cand_" + candidateHash.substring(7, 31));
    candidate.put("signature", signer.signCandidateHash(candidateHash));
    byte[] manifestBytes = Sc967CanonicalJson.canonical(manifest).getBytes(StandardCharsets.UTF_8);

    List<CandidateExecutionClosureV1.ArtifactBytes> dependencies = new ArrayList<>();
    for (JsonNode name : inputs.path("dependencyJarNames")) {
      dependencies.add(closureJar(name.asText()));
    }
    CandidateExecutionClosureV1.Inputs closureInputs =
        new CandidateExecutionClosureV1.Inputs(
            inputs.path("taskCoreEntry").asText(),
            inputs.path("runnerVersion").asText(),
            inputs.path("adapterVersion").asText(),
            inputs.path("protocolVersion").asText(),
            closureJar(inputs.path("runnerJarName").asText()),
            inputs.path("entrypoint").asText(),
            inputs.path("coreId").asText(),
            inputs.path("coreVersion").asText(),
            inputs.path("coreSourceDigest").asText(),
            closureJar(inputs.path("coreJarName").asText()),
            inputs.path("coreEntryClass").asText(),
            dependencies);
    byte[] closure =
        CandidateExecutionClosureV1.build(
            Sc967CanonicalJson.parse(manifestBytes), signedJar, closureInputs, signer);

    bundle =
        Files.createDirectories(
            temp.resolve("staging").resolve(candidate.path("candidate_id").asText()));
    Files.write(bundle.resolve("manifest.json"), manifestBytes);
    Files.write(bundle.resolve("certification.json"), certificationBytes);
    Files.write(bundle.resolve(jarName), signedJar);
    Files.write(bundle.resolve(sbomName), sbom);
    Files.write(bundle.resolve("execution-closure.json"), closure);
    for (CandidateExecutionClosureV1.ArtifactBytes jar : dependencies) {
      Files.write(bundle.resolve(jar.name()), jar.bytes());
    }
    Files.write(bundle.resolve(closureInputs.runner().name()), closureInputs.runner().bytes());
    Files.write(bundle.resolve(closureInputs.core().name()), closureInputs.core().bytes());
  }

  /** The registered candidate the workflow hands out, as a mock. */
  static PatchCandidateWorkflowService workflow() {
    PatchCandidateWorkflowService workflow = mock(PatchCandidateWorkflowService.class);
    when(workflow.registeredCandidate(PROPOSAL))
        .thenReturn(
            new PatchCandidateWorkflowService.RegisteredCandidate(
                PROPOSAL, bundle.getFileName().toString(), bundle, jarName, sbomName));
    return workflow;
  }

  /** The closure JARs the host process runs from. */
  static List<String> hostClasspath() {
    List<String> classpath = new ArrayList<>();
    classpath.add(closureDir.resolve(inputs.path("runnerJarName").asText()).toString());
    classpath.add(closureDir.resolve(inputs.path("coreJarName").asText()).toString());
    for (JsonNode name : inputs.path("dependencyJarNames")) {
      classpath.add(closureDir.resolve(name.asText()).toString());
    }
    return classpath;
  }

  /** Execution properties for the real host under {@code root}, trusting {@code policy}. */
  static Sc2106ExecutionProperties properties(Path root, Path policy) {
    Sc2106ExecutionProperties properties = new Sc2106ExecutionProperties();
    properties.setEnabled(true);
    properties.setStoreRoot(root.resolve("store").toString());
    properties.setWorkspaceRoot(root.resolve("workspaces").toString());
    properties.setRunRoot(root.resolve("runs").toString());
    properties.setVerifierPolicyVersion(POLICY_VERSION);
    properties.setHostCommand(
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            String.join(File.pathSeparator, hostClasspath()),
            HOST_MAIN,
            "--run"));
    properties.setHostEnvironment(Map.of("SC2106_TRUST_POLICY", policy.toString()));
    properties.setWorkerCommand(List.of("node", "dist/worker/main.js", "--stdio"));
    properties.setWorkerDirectory(xrspsPackage.toString());
    properties.setDevelopmentWorker(true);
    properties.setHostTimeout(java.time.Duration.ofMinutes(5));
    return properties;
  }

  static Path policy(Path temp, String name, PublicKey key) throws Exception {
    Map<String, Object> trusted = new LinkedHashMap<>();
    trusted.put("key_id", keyId);
    trusted.put(
        "public_key_pem",
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getEncoder().encodeToString(key.getEncoded())
            + "\n-----END PUBLIC KEY-----\n");
    Map<String, Object> core = new LinkedHashMap<>();
    core.put("id", inputs.path("coreId").asText());
    core.put("version", inputs.path("coreVersion").asText());
    core.put("source_digest", inputs.path("coreSourceDigest").asText());
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("schema", "sc2106-trust-policy/v1");
    policy.put("policy_version", POLICY_VERSION);
    policy.put("trusted_keys", List.of(trusted));
    policy.put("protocol_version", inputs.path("protocolVersion").asText());
    policy.put("core", core);
    policy.put("task_core_entry", inputs.path("taskCoreEntry").asText());
    policy.put("runner_version", inputs.path("runnerVersion").asText());
    policy.put("adapter_version", inputs.path("adapterVersion").asText());
    Path file = temp.resolve("trust-policy-" + name + ".json");
    Files.write(file, MAPPER.writeValueAsBytes(policy));
    return file;
  }

  static CandidateExecutionJobService.JobSpec spec(String jobId) throws Exception {
    return spec(jobId, 170);
  }

  /** A Fishing job from the authored start at level 2 with {@code xp} experience. */
  static CandidateExecutionJobService.JobSpec spec(String jobId, int xp) throws Exception {
    Map<String, Object> task = new LinkedHashMap<>();
    task.put("taskKey", "FISHING_TRAINING");
    task.put("settings", settings());
    return new CandidateExecutionJobService.JobSpec(
        jobId,
        "key-" + jobId,
        "baseline-e2e",
        "scenario-e2e",
        null,
        "content-1",
        "cache-1",
        CandidateExecutionJobServiceTest.PROFILE,
        "rec-java-rpc",
        "AUTHORED",
        task,
        taskHash(),
        start(xp),
        null,
        "java-rpc-seed",
        "task-seed-1",
        "mouse-seed-1",
        new CandidateExecutionJobService.Budgets(200, -1, -1, 120_000, -1, 1 << 20));
  }

  private static Map<String, Object> settings() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("targetFish", "SHRIMP");
    settings.put("locationName", "Al Kharid");
    settings.put("bankingMode", "DROP");
    settings.put("dropOrder", "STANDARD");
    settings.put("slotPolicy", "CLUE_SAFE");
    settings.put("useBank", false);
    settings.put("useDepositBox", false);
    settings.put("useEchoHarpoon", false);
    settings.put("bankClueBottles", true);
    settings.put("bankCaskets", true);
    settings.put("stopCondition", "Level Target");
    settings.put("stopConditionValue", 3);
    settings.put("supplyBudget", 20000);
    return settings;
  }

  /** The definition hash from the real core JAR's normalizer, the one the runner checks. */
  private static String taskHash() throws Exception {
    URL[] urls = {closureDir.resolve(inputs.path("coreJarName").asText()).toUri().toURL()};
    try (URLClassLoader loader = new URLClassLoader(urls, null)) {
      Class<?> normalizer = loader.loadClass(NORMALIZER);
      Method normalize = normalizer.getMethod("normalize", String.class, Map.class);
      Object result = normalize.invoke(null, "FISHING_TRAINING", settings());
      Object definition = result.getClass().getMethod("getDefinition").invoke(result);
      return (String) definition.getClass().getMethod("getDefinitionHash").invoke(definition);
    }
  }

  /**
   * The events of an attempt's durable journal, read with Micro6's own journal reader from the
   * closure JAR (a reader verifies the whole chain); empty while nothing is published yet.
   */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> journalEvents(Path journalRoot, String attemptId) {
    List<URL> urls = new ArrayList<>();
    try {
      for (String jar : hostClasspath()) {
        urls.add(Path.of(jar).toUri().toURL());
      }
      try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null)) {
        Class<?> journal = loader.loadClass(JOURNAL);
        Class<?> limits = loader.loadClass(JOURNAL + "$Limits");
        Object reader =
            journal
                .getConstructor(Path.class, limits)
                .newInstance(
                    journalRoot,
                    limits.getConstructor(int.class, int.class).newInstance(64, 1 << 20));
        Object export = journal.getMethod("export", String.class).invoke(reader, attemptId);
        return (List<Map<String, Object>>) export.getClass().getMethod("getEvents").invoke(export);
      }
    } catch (ReflectiveOperationException | java.io.IOException e) {
      return List.of();
    }
  }

  /** The authored stdio start Micro6's round-trip and packaged-run tests use, at level 2. */
  private static Map<String, Object> start(int xp) throws Exception {
    Process process =
        new ProcessBuilder("node", "dist/worker/main.js", "--capabilities")
            .directory(xrspsPackage.toFile())
            .start();
    byte[] bytes = process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    JsonNode xrsps = MAPPER.readTree(bytes).path("xrsps");

    Map<String, Object> base = new LinkedHashMap<>();
    base.put("manifestVersion", 1);
    base.put("manifestId", "manifest-java-rpc");
    Map<String, Object> recording = new LinkedHashMap<>();
    recording.put("recordingId", "rec-java-rpc");
    recording.put("recordingChecksum", "sha256:" + "aa".repeat(32));
    recording.put("debugSessionId", "s");
    recording.put("debugSessionChecksum", "sha256:" + "bb".repeat(32));
    recording.put("startTick", 0);
    recording.put("endTick", 2000);
    base.put("recording", recording);
    Map<String, Object> curation = new LinkedHashMap<>();
    curation.put("curationId", "cur");
    curation.put("version", 1);
    curation.put("checksum", "sha256:" + "cc".repeat(32));
    base.put("curation", curation);
    base.put("compiler", Map.of("version", "t", "schemaVersion", 1));
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("xrspsAdapterVersion", text(xrsps, "adapterVersion"));
    runtime.put("xrspsBuildCommit", text(xrsps, "buildCommit"));
    runtime.put("cacheRevision", text(xrsps, "cacheRevision"));
    runtime.put("contentRevision", text(xrsps, "contentRevision"));
    base.put("runtime", runtime);
    Map<String, Object> determinism = new LinkedHashMap<>();
    determinism.put("seed", "java-rpc-seed");
    determinism.put("clockOrigin", "2026-01-01T00:00:00.000Z");
    determinism.put("tickDurationMs", 600);
    determinism.put("idNamespace", "replay");
    base.put("determinism", determinism);
    Map<String, Object> player = new LinkedHashMap<>();
    player.put("position", Map.of("x", 100, "y", 100, "plane", 0));
    player.put("skills", Map.of("fishing", Map.of("level", 2, "xp", xp)));
    player.put("inventory", List.of(Map.of("slot", 0, "itemId", 303, "quantity", 1)));
    Map<String, Object> spot = new LinkedHashMap<>();
    spot.put("stableId", "spot:near");
    spot.put("npcId", 1530);
    spot.put("position", Map.of("x", 101, "y", 100, "plane", 0));
    spot.put("spotType", "net");
    // The cache actions of NPC 1530; the pinned profile rejects actions the NPC lacks.
    spot.put("actions", List.of("Net", "Bait"));
    Map<String, Object> initialState = new LinkedHashMap<>();
    initialState.put("worldId", 0);
    initialState.put("player", player);
    initialState.put("npcs", List.of(spot));
    base.put("initialState", initialState);
    base.put("actions", List.of());
    base.put("expectations", List.of());
    base.put("tolerances", Map.of());
    base.put("controlAllowed", false);
    Map<String, Object> manifest = new LinkedHashMap<>(base);
    manifest.put("manifestChecksum", Sc2106CanonicalJson.sha256(base));

    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("itemNames", Map.of("303", "Small fishing net", "317", "Raw shrimps"));
    // loggedIn/moving/busy/paused come from worker state in a profile-qualified session (R3.2).
    facts.put("onScreen", true);
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("manifest", manifest);
    start.put("facts", facts);
    start.put("supportedCapabilities", List.of("INTERACT_NPC", "INVENTORY_ACTION"));
    return start;
  }

  private static Object text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static CandidateExecutionClosureV1.ArtifactBytes closureJar(String name)
      throws Exception {
    return new CandidateExecutionClosureV1.ArtifactBytes(
        name, Files.readAllBytes(closureDir.resolve(name)));
  }

  private static byte[] artifact(String kind) {
    return Base64.getDecoder()
        .decode(vectors.path("artifacts").path(kind).path("content").asText());
  }

  static String required(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new AssertionError(property + " is required (see :sc2106HostIntegrationTest)");
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  static String sha(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(Sc967CanonicalJson.digest(bytes));
  }
}
