package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-capability readiness ladder for the capability report (W13). Each rung implies the ones
 * before it, so the report never claims more than the weakest condition supports:
 *
 * <ul>
 *   <li><b>configured</b>: the operator switched it on and supplied what it needs;
 *   <li><b>available</b>: the tools/files it needs exist on this host;
 *   <li><b>runnable</b>: jobs can execute here (containment and bounded workspaces work, and the
 *       restricted launcher is available wherever it is required);
 *   <li><b>isolated</b>: executions run in a systemd scope under the restricted launcher (network
 *       namespace, per-job writable mount, disk quota);
 *   <li><b>qualified</b>: recorded by the operator in {@code qualified-capabilities} after its
 *       evidence was accepted. It is never inferred from the host.
 * </ul>
 */
@Component
public class MouseVideoReadiness {

  public static final String MEDIA_IMPORT = "media-import";
  public static final String CURSOR_TRACKING = "cursor-tracking";
  public static final String YOUTUBE_ACQUISITION = "youtube-acquisition";
  public static final String REVIEW_PROTOCOL = "review-protocol";

  private final MouseVideoProperties properties;
  private final BooleanSupplier ffprobe;
  private final BooleanSupplier ffmpeg;
  private final BooleanSupplier launcherAvailable;
  private final BooleanSupplier scopes;
  private final BooleanSupplier workspaceTools;

  @Autowired
  public MouseVideoReadiness(
      MouseVideoProperties properties,
      MouseVideoProbeWorker probeWorker,
      MouseVideoDerivativeWorker derivativeWorker,
      MouseVideoRestrictedLauncher launcher) {
    this(
        properties,
        probeWorker::ffprobeAvailable,
        derivativeWorker::ffmpegAvailable,
        launcher::available,
        MouseVideoProcessRunner::scopeContainmentAvailable,
        MouseVideoWorkspace::toolsAvailable);
  }

  /** Test seam over the host probes. */
  MouseVideoReadiness(
      MouseVideoProperties properties,
      BooleanSupplier ffprobe,
      BooleanSupplier ffmpeg,
      BooleanSupplier launcherAvailable,
      BooleanSupplier scopes,
      BooleanSupplier workspaceTools) {
    this.properties = properties;
    this.ffprobe = ffprobe;
    this.ffmpeg = ffmpeg;
    this.launcherAvailable = launcherAvailable;
    this.scopes = scopes;
    this.workspaceTools = workspaceTools;
  }

  /** One ladder per capability, plus the qualification ids this deployment recorded. */
  public Map<String, Object> report() {
    Set<String> qualified =
        Arrays.stream(properties.getQualifiedCapabilities().split(","))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toCollection(java.util.TreeSet::new));
    boolean launcherRequired = properties.isRestrictedLauncher();
    boolean launcher = launcherAvailable.getAsBoolean();
    boolean scoped = scopes.getAsBoolean();
    boolean executionsRun =
        scoped && workspaceTools.getAsBoolean() && (!launcherRequired || launcher);
    boolean executionsIsolated = executionsRun && launcherRequired && launcher;
    boolean tools = ffprobe.getAsBoolean() && ffmpeg.getAsBoolean();

    Map<String, Object> body = new LinkedHashMap<>();
    boolean importConfigured = properties.isEnabled();
    body.put(
        MEDIA_IMPORT,
        ladder(
            importConfigured, tools, executionsRun, executionsIsolated, qualified, MEDIA_IMPORT));
    boolean template = isFile(properties.getVisualCursorTemplate());
    body.put(
        CURSOR_TRACKING,
        ladder(
            importConfigured && template,
            tools,
            executionsRun,
            executionsIsolated,
            qualified,
            CURSOR_TRACKING));
    String downloader = properties.getYoutubeDownloader();
    body.put(
        YOUTUBE_ACQUISITION,
        ladder(
            importConfigured
                && properties.isYoutubeEnabled()
                && downloader != null
                && !downloader.isBlank(),
            tools && downloader != null && !downloader.isBlank() && isExecutable(downloader),
            executionsRun,
            executionsIsolated,
            qualified,
            YOUTUBE_ACQUISITION));
    String manifest = properties.getCorpusManifest();
    boolean policy = isFile(manifest);
    // The review protocol runs in this JVM against the database; it starts no executions, so its
    // isolation rung is its policy boundary (operator-owned, never browser-supplied).
    body.put(
        REVIEW_PROTOCOL,
        ladder(
            importConfigured && manifest != null && !manifest.isBlank(),
            policy,
            policy,
            policy,
            qualified,
            REVIEW_PROTOCOL));
    body.put("qualifiedRecorded", qualified);
    return body;
  }

  private static Map<String, Object> ladder(
      boolean configured,
      boolean available,
      boolean runnable,
      boolean isolated,
      Set<String> qualifiedIds,
      String id) {
    boolean c = configured;
    boolean a = c && available;
    boolean r = a && runnable;
    boolean i = r && isolated;
    boolean q = i && qualifiedIds.contains(id);
    Map<String, Object> rung = new LinkedHashMap<>();
    rung.put("configured", c);
    rung.put("available", a);
    rung.put("runnable", r);
    rung.put("isolated", i);
    rung.put("qualified", q);
    if (!q) {
      rung.put(
          "limitedBy",
          !c
              ? "configured"
              : !a ? "available" : !r ? "runnable" : !i ? "isolated" : "no qualification recorded");
    }
    return rung;
  }

  private static boolean isFile(String path) {
    return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path));
  }

  private static boolean isExecutable(String path) {
    return Files.isExecutable(Path.of(path));
  }
}
