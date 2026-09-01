package com.chromascape.web.replay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code replay.*} configuration. All xRSPS paths must be set in
 * server configuration, never accepted from the browser.
 */
@ConfigurationProperties(prefix = "replay")
@Validated
public class ReplayProperties {

    private boolean enabled;

    @Valid private final Xrsps xrsps = new Xrsps();
    @Valid private final Worker worker = new Worker();
    @Valid private final Artifacts artifacts = new Artifacts();
    @Valid private final Viewer viewer = new Viewer();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Xrsps getXrsps() {
        return xrsps;
    }

    public Worker getWorker() {
        return worker;
    }

    public Artifacts getArtifacts() {
        return artifacts;
    }

    public Viewer getViewer() {
        return viewer;
    }

    /** xRSPS kernel process configuration. Never exposed to the browser. */
    public static class Xrsps {

        @NotBlank private String executable = "node";

        private String entrypoint;

        private String workingDirectory;

        private String expectedAdapterVersion;

        private String expectedBuildCommit;

        private String expectedCacheRevision;

        private String expectedContentRevision;

        @Positive private long startupTimeoutMs = 15000;

        @Positive private long requestTimeoutMs = 30000;

        @Positive private long shutdownTimeoutMs = 5000;

        @Positive private long maxStdoutLineBytes = 10_485_760;

        @Positive private long maxStderrBytes = 1_048_576;

        /** Bounded response queue capacity; the kernel is strictly one-request-at-a-time. */
        @Positive private int responseQueueCapacity = 16;

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public String getEntrypoint() {
            return entrypoint;
        }

        public void setEntrypoint(String entrypoint) {
            this.entrypoint = entrypoint;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
        }

        public String getExpectedAdapterVersion() {
            return expectedAdapterVersion;
        }

        public void setExpectedAdapterVersion(String expectedAdapterVersion) {
            this.expectedAdapterVersion = expectedAdapterVersion;
        }

        public String getExpectedBuildCommit() {
            return expectedBuildCommit;
        }

        public void setExpectedBuildCommit(String expectedBuildCommit) {
            this.expectedBuildCommit = expectedBuildCommit;
        }

        public String getExpectedCacheRevision() {
            return expectedCacheRevision;
        }

        public void setExpectedCacheRevision(String expectedCacheRevision) {
            this.expectedCacheRevision = expectedCacheRevision;
        }

        public String getExpectedContentRevision() {
            return expectedContentRevision;
        }

        public void setExpectedContentRevision(String expectedContentRevision) {
            this.expectedContentRevision = expectedContentRevision;
        }

        public long getStartupTimeoutMs() {
            return startupTimeoutMs;
        }

        public void setStartupTimeoutMs(long startupTimeoutMs) {
            this.startupTimeoutMs = startupTimeoutMs;
        }

        public long getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public long getShutdownTimeoutMs() {
            return shutdownTimeoutMs;
        }

        public void setShutdownTimeoutMs(long shutdownTimeoutMs) {
            this.shutdownTimeoutMs = shutdownTimeoutMs;
        }

        public long getMaxStdoutLineBytes() {
            return maxStdoutLineBytes;
        }

        public void setMaxStdoutLineBytes(long maxStdoutLineBytes) {
            this.maxStdoutLineBytes = maxStdoutLineBytes;
        }

        public long getMaxStderrBytes() {
            return maxStderrBytes;
        }

        public void setMaxStderrBytes(long maxStderrBytes) {
            this.maxStderrBytes = maxStderrBytes;
        }

        public int getResponseQueueCapacity() {
            return responseQueueCapacity;
        }

        public void setResponseQueueCapacity(int responseQueueCapacity) {
            this.responseQueueCapacity = responseQueueCapacity;
        }
    }

    /** Worker scheduling configuration. */
    public static class Worker {

        private boolean enabled;

        @Positive private int concurrency = 2;

        @Min(5)
        private int leaseDurationSeconds = 30;

        @Min(1)
        private int heartbeatSeconds = 10;

        @Positive private long pollIntervalMs = 1000;

        @Positive private long runTimeoutSeconds = 300;

        @Positive private int maxEventBatch = 100;

        @Min(0)
        private int maxRetries = 2;

        private boolean missingReportRepairEnabled = true;

        @Positive private int missingReportRepairBatchSize = 25;

        @Positive private int missingReportRepairIntervalSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getLeaseDurationSeconds() {
            return leaseDurationSeconds;
        }

        public void setLeaseDurationSeconds(int leaseDurationSeconds) {
            this.leaseDurationSeconds = leaseDurationSeconds;
        }

        public int getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public void setHeartbeatSeconds(int heartbeatSeconds) {
            this.heartbeatSeconds = heartbeatSeconds;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getRunTimeoutSeconds() {
            return runTimeoutSeconds;
        }

        public void setRunTimeoutSeconds(long runTimeoutSeconds) {
            this.runTimeoutSeconds = runTimeoutSeconds;
        }

        public int getMaxEventBatch() {
            return maxEventBatch;
        }

        public void setMaxEventBatch(int maxEventBatch) {
            this.maxEventBatch = maxEventBatch;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isMissingReportRepairEnabled() {
            return missingReportRepairEnabled;
        }

        public void setMissingReportRepairEnabled(boolean missingReportRepairEnabled) {
            this.missingReportRepairEnabled = missingReportRepairEnabled;
        }

        public int getMissingReportRepairBatchSize() {
            return missingReportRepairBatchSize;
        }

        public void setMissingReportRepairBatchSize(int missingReportRepairBatchSize) {
            this.missingReportRepairBatchSize = missingReportRepairBatchSize;
        }

        public int getMissingReportRepairIntervalSeconds() {
            return missingReportRepairIntervalSeconds;
        }

        public void setMissingReportRepairIntervalSeconds(int missingReportRepairIntervalSeconds) {
            this.missingReportRepairIntervalSeconds = missingReportRepairIntervalSeconds;
        }
    }

    /** Artifact storage configuration. */
    public static class Artifacts {

        @NotBlank private String root = ".chromascape/replay-artifacts";

        @Positive private long maxArtifactBytes = 104_857_600;

        @Positive private int retainFailedStagingHours = 24;

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public long getMaxArtifactBytes() {
            return maxArtifactBytes;
        }

        public void setMaxArtifactBytes(long maxArtifactBytes) {
            this.maxArtifactBytes = maxArtifactBytes;
        }

        public int getRetainFailedStagingHours() {
            return retainFailedStagingHours;
        }

        public void setRetainFailedStagingHours(int retainFailedStagingHours) {
            this.retainFailedStagingHours = retainFailedStagingHours;
        }
    }

    /** Read-only xRSPS WebGL viewer configuration. Secrets never come from a request. */
    public static class Viewer {

        private boolean enabled;

        @NotBlank private String origin = "http://127.0.0.1:3000";

        private String signingSecret;

        @Positive private long tokenTtlSeconds = 300;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOrigin() {
            return origin;
        }

        public void setOrigin(String origin) {
            this.origin = origin;
        }

        public String getSigningSecret() {
            return signingSecret;
        }

        public void setSigningSecret(String signingSecret) {
            this.signingSecret = signingSecret;
        }

        public long getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }
    }
}
