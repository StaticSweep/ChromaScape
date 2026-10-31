package com.chromascape.web.debug.tickdebugger.video;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Operator-configured bounds and capability switches for external video import and analysis.
 * Defaults are the reviewed release values from the SC-2150 plan; they are recorded per attempt and
 * can only be changed by a server operator. The feature is disabled until explicitly enabled.
 */
@Component
@ConfigurationProperties(prefix = "chromascape.mouse-video")
public class MouseVideoProperties {

  /** Operator-owned, bounded corpus policy file; never supplied by a browser. */
  private String corpusManifest = "";

  public String getCorpusManifest() {
    return corpusManifest;
  }

  public void setCorpusManifest(String value) {
    corpusManifest = value;
  }

  /** Master switch for external video import and analysis. */
  private boolean enabled = false;

  /** Independently gates the pinned YouTube acquisition path. */
  private boolean youtubeEnabled = false;

  /** Owned root for the byte store, staging directories and per-job workspaces. */
  private String directory = "data/mouse-video";

  /** Python interpreter used to run the fixed server-owned probe worker. */
  private String python = "python3";

  /** ffprobe binary used by the probe worker for bounded stream metadata. */
  private String ffprobe = "ffprobe";

  /** ffmpeg binary used to derive browser proxies, thumbnails and the frame index. */
  private String ffmpeg = "ffmpeg";

  /** Pinned YouTube downloader executable; empty keeps acquisition unavailable. */
  private String youtubeDownloader = "";

  /** Expected SHA-256 of the pinned YouTube downloader executable, if configured. */
  private String youtubeDownloaderSha256 = "";

  /** Optional fixed HMAC secret for browser write nonces; blank generates an ephemeral secret. */
  private String writeNonceSecret = "";

  /** Lifetime of a server-issued browser write nonce. */
  private long writeNonceTtlSeconds = 600L;

  /** Grace period before an unreferenced published byte object may be swept. */
  private long orphanGraceSeconds = 86_400L;

  /** Maximum decoded frames retained in the bounded original frame/PTS index. */
  private long maxIndexedFrames = 150_000L;

  /** Maximum proxy height; width is derived from the source aspect ratio. */
  private int proxyMaxHeight = 720;

  /** Optional proxy video encoder; blank auto-selects libx264, libopenh264 or h264. */
  private String proxyEncoder = "";

  private long maxUploadBytes = 512L * 1024 * 1024;
  private long maxDurationSeconds = 20L * 60L;
  private int maxWidth = 3840;
  private int maxHeight = 2160;
  private double maxFps = 120.0d;
  private long maxDecodeFrames = 150_000L;
  private long maxSourceDiskBytes = 4L * 1024 * 1024 * 1024;

  /** Aggregate workspace volume plus private sandbox temporary-filesystem budget. */
  private long maxJobDiskBytes = 4L * 1024 * 1024 * 1024;

  private int connectTimeoutSeconds = 30;
  private long acquireBudgetSeconds = 600L;
  private long probeBudgetSeconds = 30L;
  private long analysisBudgetSeconds = 1_200L;
  private long childShutdownBudgetSeconds = 5L;

  /**
   * Hard per-execution memory budget. Enforced as a cgroup {@code MemoryMax} with swap disabled
   * ({@code MemorySwapMax=0}), so over-budget allocations are killed instead of being hidden in
   * swap.
   */
  private long maxRssBytes = 2L * 1024 * 1024 * 1024;

  private int waitingSlots = 8;
  private long leaseHeartbeatSeconds = 5L;
  private long leaseExpirySeconds = 30L;
  private int maxCatalogPageSize = 25;
  private int maxObservationPageSize = 500;

  /** Fixed server-owned identity of the shipped visual producer. */
  private String visualToolchainPin = "mouse-video-visual/1";

  /** Sampling rate for visual observations, clamped to the frozen 60 fps ceiling. */
  private double visualSampleFps = 30.0d;

  /** Hard cap on frames decoded by the visual producer, independent of the source frame count. */
  private int maxVisualFrames = 3_600;

  /** Maximum width of the grayscale analysis samples; wider sources are area-downscaled. */
  private int visualSampleMaxWidth = 480;

  /** Minimum contiguous low-motion interval that may be described as a pause. */
  private long pauseMinMillis = 250L;

  /** Normalized mean absolute luminance delta at or below which a sampled frame is low motion. */
  private double pauseMaxDelta = 0.004d;

  /** Normalized delta at or above which a sampled pair is a scene cut candidate. */
  private double cutMinDelta = 0.22d;

  /**
   * A smaller frame difference is still a cut when it is at least {@code cutSpikeRatio} times both
   * adjacent sampled differences: an isolated spike, unlike sustained camera motion.
   */
  private double cutSpikeMinDelta = 0.04d;

  private double cutSpikeRatio = 4.0d;

  /** Normalized delta at or above which a non-cut frame pair is a camera/activity candidate. */
  private double activityMinDelta = 0.05d;

  /** Optional reviewed cursor template PNG; absent keeps cursor tracking explicitly unknown. */
  private String visualCursorTemplate = "";

  private int visualCursorHotspotX;
  private int visualCursorHotspotY;

  /**
   * cursor-template/v2 thresholds: minimum masked zero-mean correlation, accepted luma gain range,
   * minimum patch standard deviation (grey levels) and the runner-up margin below which a frame is
   * ambiguous. Tuned on the SC-2150 FA12 development split only; see docs/mouse-video-import.md.
   */
  private double visualCursorMinScore = 0.85d;

  /** Stricter score a detection needs when it does not continue the previous one (hysteresis). */
  private double visualCursorStartScore = 0.88d;

  private double visualCursorMinGain = 0.35d;
  private double visualCursorMaxGain = 1.6d;
  private double visualCursorMinContrast = 12.0d;
  private double visualCursorAmbiguityMargin = 0.05d;

  /** Mean absolute luma change below which a placement is unchanged versus its neighbours. */
  private double visualCursorStaticMaxDelta = 6.0d;

  /** Maximum output bounds for an exact-frame preview. */
  private int maxPreviewWidth = 1_920;

  private int maxPreviewHeight = 1_080;

  /** Requires the bwrap/systemd restricted launcher for decoder and analysis children. */
  private boolean restrictedLauncher = false;

  private int cpuQuotaPercent = 200;
  private int tasksMax = 64;
  private long jobVolumeBytes = 4L * 1024 * 1024 * 1024;

  public String getVisualToolchainPin() {
    return visualToolchainPin;
  }

  public void setVisualToolchainPin(String visualToolchainPin) {
    this.visualToolchainPin = visualToolchainPin;
  }

  public double getVisualSampleFps() {
    return visualSampleFps;
  }

  public void setVisualSampleFps(double visualSampleFps) {
    this.visualSampleFps = visualSampleFps;
  }

  public int getMaxVisualFrames() {
    return maxVisualFrames;
  }

  public int getVisualSampleMaxWidth() {
    return visualSampleMaxWidth;
  }

  public void setVisualSampleMaxWidth(int visualSampleMaxWidth) {
    this.visualSampleMaxWidth = visualSampleMaxWidth;
  }

  public void setMaxVisualFrames(int maxVisualFrames) {
    this.maxVisualFrames = maxVisualFrames;
  }

  public long getPauseMinMillis() {
    return pauseMinMillis;
  }

  public void setPauseMinMillis(long pauseMinMillis) {
    this.pauseMinMillis = pauseMinMillis;
  }

  public double getPauseMaxDelta() {
    return pauseMaxDelta;
  }

  public void setPauseMaxDelta(double pauseMaxDelta) {
    this.pauseMaxDelta = pauseMaxDelta;
  }

  public double getCutMinDelta() {
    return cutMinDelta;
  }

  public void setCutMinDelta(double cutMinDelta) {
    this.cutMinDelta = cutMinDelta;
  }

  public double getActivityMinDelta() {
    return activityMinDelta;
  }

  public void setActivityMinDelta(double activityMinDelta) {
    this.activityMinDelta = activityMinDelta;
  }

  public String getVisualCursorTemplate() {
    return visualCursorTemplate;
  }

  public void setVisualCursorTemplate(String visualCursorTemplate) {
    this.visualCursorTemplate = visualCursorTemplate;
  }

  public int getVisualCursorHotspotX() {
    return visualCursorHotspotX;
  }

  public void setVisualCursorHotspotX(int visualCursorHotspotX) {
    this.visualCursorHotspotX = visualCursorHotspotX;
  }

  public int getVisualCursorHotspotY() {
    return visualCursorHotspotY;
  }

  public void setVisualCursorHotspotY(int visualCursorHotspotY) {
    this.visualCursorHotspotY = visualCursorHotspotY;
  }

  public double getVisualCursorMinScore() {
    return visualCursorMinScore;
  }

  public void setVisualCursorMinScore(double visualCursorMinScore) {
    this.visualCursorMinScore = visualCursorMinScore;
  }

  public double getVisualCursorStartScore() {
    return visualCursorStartScore;
  }

  public void setVisualCursorStartScore(double visualCursorStartScore) {
    this.visualCursorStartScore = visualCursorStartScore;
  }

  public double getVisualCursorMinGain() {
    return visualCursorMinGain;
  }

  public void setVisualCursorMinGain(double visualCursorMinGain) {
    this.visualCursorMinGain = visualCursorMinGain;
  }

  public double getVisualCursorMaxGain() {
    return visualCursorMaxGain;
  }

  public void setVisualCursorMaxGain(double visualCursorMaxGain) {
    this.visualCursorMaxGain = visualCursorMaxGain;
  }

  public double getVisualCursorMinContrast() {
    return visualCursorMinContrast;
  }

  public void setVisualCursorMinContrast(double visualCursorMinContrast) {
    this.visualCursorMinContrast = visualCursorMinContrast;
  }

  public double getVisualCursorAmbiguityMargin() {
    return visualCursorAmbiguityMargin;
  }

  public void setVisualCursorAmbiguityMargin(double visualCursorAmbiguityMargin) {
    this.visualCursorAmbiguityMargin = visualCursorAmbiguityMargin;
  }

  public double getVisualCursorStaticMaxDelta() {
    return visualCursorStaticMaxDelta;
  }

  public void setVisualCursorStaticMaxDelta(double visualCursorStaticMaxDelta) {
    this.visualCursorStaticMaxDelta = visualCursorStaticMaxDelta;
  }

  public double getCutSpikeMinDelta() {
    return cutSpikeMinDelta;
  }

  public void setCutSpikeMinDelta(double cutSpikeMinDelta) {
    this.cutSpikeMinDelta = cutSpikeMinDelta;
  }

  public double getCutSpikeRatio() {
    return cutSpikeRatio;
  }

  public void setCutSpikeRatio(double cutSpikeRatio) {
    this.cutSpikeRatio = cutSpikeRatio;
  }

  public int getMaxPreviewWidth() {
    return maxPreviewWidth;
  }

  public void setMaxPreviewWidth(int maxPreviewWidth) {
    this.maxPreviewWidth = maxPreviewWidth;
  }

  public int getMaxPreviewHeight() {
    return maxPreviewHeight;
  }

  public void setMaxPreviewHeight(int maxPreviewHeight) {
    this.maxPreviewHeight = maxPreviewHeight;
  }

  public boolean isRestrictedLauncher() {
    return restrictedLauncher;
  }

  public void setRestrictedLauncher(boolean restrictedLauncher) {
    this.restrictedLauncher = restrictedLauncher;
  }

  public int getCpuQuotaPercent() {
    return cpuQuotaPercent;
  }

  public void setCpuQuotaPercent(int cpuQuotaPercent) {
    this.cpuQuotaPercent = cpuQuotaPercent;
  }

  public int getTasksMax() {
    return tasksMax;
  }

  public void setTasksMax(int tasksMax) {
    this.tasksMax = tasksMax;
  }

  public long getJobVolumeBytes() {
    return jobVolumeBytes;
  }

  public void setJobVolumeBytes(long jobVolumeBytes) {
    this.jobVolumeBytes = jobVolumeBytes;
  }

  public Path rootPath() {
    return Path.of(directory).toAbsolutePath().normalize();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isYoutubeEnabled() {
    return youtubeEnabled;
  }

  public void setYoutubeEnabled(boolean youtubeEnabled) {
    this.youtubeEnabled = youtubeEnabled;
  }

  public String getDirectory() {
    return directory;
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }

  public String getPython() {
    return python;
  }

  public void setPython(String python) {
    this.python = python;
  }

  public String getFfprobe() {
    return ffprobe;
  }

  public void setFfprobe(String ffprobe) {
    this.ffprobe = ffprobe;
  }

  public String getFfmpeg() {
    return ffmpeg;
  }

  public void setFfmpeg(String ffmpeg) {
    this.ffmpeg = ffmpeg;
  }

  public String getYoutubeDownloader() {
    return youtubeDownloader;
  }

  public void setYoutubeDownloader(String youtubeDownloader) {
    this.youtubeDownloader = youtubeDownloader;
  }

  public String getYoutubeDownloaderSha256() {
    return youtubeDownloaderSha256;
  }

  public void setYoutubeDownloaderSha256(String youtubeDownloaderSha256) {
    this.youtubeDownloaderSha256 = youtubeDownloaderSha256;
  }

  public String getWriteNonceSecret() {
    return writeNonceSecret;
  }

  public void setWriteNonceSecret(String writeNonceSecret) {
    this.writeNonceSecret = writeNonceSecret;
  }

  public long getWriteNonceTtlSeconds() {
    return writeNonceTtlSeconds;
  }

  public void setWriteNonceTtlSeconds(long writeNonceTtlSeconds) {
    this.writeNonceTtlSeconds = writeNonceTtlSeconds;
  }

  public long getOrphanGraceSeconds() {
    return orphanGraceSeconds;
  }

  public void setOrphanGraceSeconds(long orphanGraceSeconds) {
    this.orphanGraceSeconds = orphanGraceSeconds;
  }

  public long getMaxIndexedFrames() {
    return maxIndexedFrames;
  }

  public void setMaxIndexedFrames(long maxIndexedFrames) {
    this.maxIndexedFrames = maxIndexedFrames;
  }

  public int getProxyMaxHeight() {
    return proxyMaxHeight;
  }

  public void setProxyMaxHeight(int proxyMaxHeight) {
    this.proxyMaxHeight = proxyMaxHeight;
  }

  public String getProxyEncoder() {
    return proxyEncoder;
  }

  public void setProxyEncoder(String proxyEncoder) {
    this.proxyEncoder = proxyEncoder;
  }

  public long getMaxUploadBytes() {
    return maxUploadBytes;
  }

  public void setMaxUploadBytes(long maxUploadBytes) {
    this.maxUploadBytes = maxUploadBytes;
  }

  public long getMaxDurationSeconds() {
    return maxDurationSeconds;
  }

  public void setMaxDurationSeconds(long maxDurationSeconds) {
    this.maxDurationSeconds = maxDurationSeconds;
  }

  public int getMaxWidth() {
    return maxWidth;
  }

  public void setMaxWidth(int maxWidth) {
    this.maxWidth = maxWidth;
  }

  public int getMaxHeight() {
    return maxHeight;
  }

  public void setMaxHeight(int maxHeight) {
    this.maxHeight = maxHeight;
  }

  public double getMaxFps() {
    return maxFps;
  }

  public void setMaxFps(double maxFps) {
    this.maxFps = maxFps;
  }

  public long getMaxDecodeFrames() {
    return maxDecodeFrames;
  }

  public void setMaxDecodeFrames(long maxDecodeFrames) {
    this.maxDecodeFrames = maxDecodeFrames;
  }

  public long getMaxSourceDiskBytes() {
    return maxSourceDiskBytes;
  }

  public void setMaxSourceDiskBytes(long maxSourceDiskBytes) {
    this.maxSourceDiskBytes = maxSourceDiskBytes;
  }

  public int getConnectTimeoutSeconds() {
    return connectTimeoutSeconds;
  }

  public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
    this.connectTimeoutSeconds = connectTimeoutSeconds;
  }

  public long getAcquireBudgetSeconds() {
    return acquireBudgetSeconds;
  }

  public void setAcquireBudgetSeconds(long acquireBudgetSeconds) {
    this.acquireBudgetSeconds = acquireBudgetSeconds;
  }

  public long getProbeBudgetSeconds() {
    return probeBudgetSeconds;
  }

  public void setProbeBudgetSeconds(long probeBudgetSeconds) {
    this.probeBudgetSeconds = probeBudgetSeconds;
  }

  public long getAnalysisBudgetSeconds() {
    return analysisBudgetSeconds;
  }

  public void setAnalysisBudgetSeconds(long analysisBudgetSeconds) {
    this.analysisBudgetSeconds = analysisBudgetSeconds;
  }

  public long getChildShutdownBudgetSeconds() {
    return childShutdownBudgetSeconds;
  }

  public void setChildShutdownBudgetSeconds(long childShutdownBudgetSeconds) {
    this.childShutdownBudgetSeconds = childShutdownBudgetSeconds;
  }

  public long getMaxRssBytes() {
    return maxRssBytes;
  }

  public long getMaxJobDiskBytes() {
    return maxJobDiskBytes;
  }

  public void setMaxJobDiskBytes(long maxJobDiskBytes) {
    this.maxJobDiskBytes = maxJobDiskBytes;
  }

  public void setMaxRssBytes(long maxRssBytes) {
    this.maxRssBytes = maxRssBytes;
  }

  public int getWaitingSlots() {
    return waitingSlots;
  }

  public void setWaitingSlots(int waitingSlots) {
    this.waitingSlots = waitingSlots;
  }

  public long getLeaseHeartbeatSeconds() {
    return leaseHeartbeatSeconds;
  }

  public void setLeaseHeartbeatSeconds(long leaseHeartbeatSeconds) {
    this.leaseHeartbeatSeconds = leaseHeartbeatSeconds;
  }

  public long getLeaseExpirySeconds() {
    return leaseExpirySeconds;
  }

  public void setLeaseExpirySeconds(long leaseExpirySeconds) {
    this.leaseExpirySeconds = leaseExpirySeconds;
  }

  public int getMaxCatalogPageSize() {
    return maxCatalogPageSize;
  }

  public void setMaxCatalogPageSize(int maxCatalogPageSize) {
    this.maxCatalogPageSize = maxCatalogPageSize;
  }

  public int getMaxObservationPageSize() {
    return maxObservationPageSize;
  }

  public void setMaxObservationPageSize(int maxObservationPageSize) {
    this.maxObservationPageSize = maxObservationPageSize;
  }
}
