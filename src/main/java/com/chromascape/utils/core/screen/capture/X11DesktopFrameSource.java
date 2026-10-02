package com.chromascape.utils.core.screen.capture;

import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A view-only frame source that watches the local X11 desktop through an ffmpeg x11grab pipeline.
 *
 * <p>No process is attached, injected, or driven: pixels are read from the shared X display exactly
 * like a screenshot tool would. This lets the debugger viewport observe a client that is driven
 * elsewhere (for example a self-driving Builder Goal run) without taking its input.
 *
 * <p>A single ffmpeg process streams downscaled raw frames on stdout; a reader thread keeps only
 * the newest frame. The source fails closed when ffmpeg is missing, exits, stalls, or is closed.
 */
public final class X11DesktopFrameSource implements ViewOnlyFrameSource {

  private static final Logger logger = LogManager.getLogger(X11DesktopFrameSource.class);

  /** Bounds for the observation rate: fast enough to watch, slow enough to stay cheap. */
  public static final int MIN_FPS = 1;

  public static final int MAX_FPS = 5;

  public static final int DEFAULT_TARGET_WIDTH = 960;

  public static final int DEFAULT_FPS = 2;

  public static final String DEFAULT_DISPLAY = ":0";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);

  private static final Pattern VIDEO_DIMENSIONS = Pattern.compile("(\\d{1,5})x(\\d{1,5})");

  private final String display;
  private final int framesPerSecond;
  private final ProcessIdentity identity =
      new ProcessIdentity(ProcessOrigin.LOCAL, "", -1, Instant.EPOCH, "");
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicLong deliveredCount = new AtomicLong(0);
  private final AtomicReference<String> failure = new AtomicReference<>(null);
  private final AtomicReference<Slot> latest = new AtomicReference<>(null);
  private final Instant startedAt = Instant.now();

  private final Process ffmpeg;
  private final Thread reader;
  private final int width;
  private final int height;
  private final int frameBytes;

  /** One decoded desktop image and the moment it arrived. */
  private record Slot(BufferedImage image, Instant arrivedAt) {}

  /**
   * Creates and starts a desktop capture pipeline on the given display.
   *
   * @param ffmpegBinary ffmpeg executable (absolute path or binary name on PATH)
   * @param display X display to watch, for example ":0"
   * @param targetWidth downscaled capture width in pixels
   * @param framesPerSecond observation rate, clamped to {@link #MIN_FPS}..{@link #MAX_FPS}
   */
  public X11DesktopFrameSource(
      String ffmpegBinary, String display, int targetWidth, int framesPerSecond) {
    if (ffmpegBinary == null || ffmpegBinary.isBlank()) {
      throw new IllegalArgumentException("ffmpeg binary must be supplied");
    }
    if (display == null || display.isBlank()) {
      throw new IllegalArgumentException("X display must be supplied");
    }
    this.display = display;
    this.framesPerSecond = Math.max(MIN_FPS, Math.min(MAX_FPS, framesPerSecond));
    int scaledWidth = Math.max(320, Math.min(1920, targetWidth));
    scaledWidth -= scaledWidth % 2;

    checkBinary(ffmpegBinary);
    List<String> command =
        List.of(
            ffmpegBinary,
            "-hide_banner",
            "-f",
            "x11grab",
            "-framerate",
            String.valueOf(this.framesPerSecond),
            "-i",
            display,
            "-vf",
            "scale=" + scaledWidth + ":-2",
            "-r",
            String.valueOf(this.framesPerSecond),
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgb24",
            "pipe:1");
    Process started;
    try {
      started = new ProcessBuilder(command).redirectErrorStream(false).start();
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Could not start the desktop capture pipeline on display " + display, failure);
    }
    this.ffmpeg = started;
    int[] size;
    try {
      size = readDesktopSize(started.getErrorStream());
    } catch (RuntimeException failure) {
      started.destroyForcibly();
      throw failure;
    }
    this.width = scaledWidth;
    int scaledHeight = (size[1] * scaledWidth) / size[0];
    scaledHeight = Math.max(2, scaledHeight - (scaledHeight % 2));
    this.height = scaledHeight;
    this.frameBytes = this.width * this.height * 3;
    Thread readerThread = new Thread(this::readLoop, "X11DesktopCapture");
    readerThread.setDaemon(true);
    readerThread.start();
    this.reader = readerThread;
    logger.info(
        "View-only desktop capture started on display {} at {}x{} ({} fps)",
        display,
        this.width,
        this.height,
        this.framesPerSecond);
  }

  /** Creates a source with default rate and width on the given display. */
  public X11DesktopFrameSource(String display) {
    this("ffmpeg", display, DEFAULT_TARGET_WIDTH, DEFAULT_FPS);
  }

  private static void checkBinary(String ffmpegBinary) {
    Process check = null;
    try {
      check =
          new ProcessBuilder(List.of(ffmpegBinary, "-version")).redirectErrorStream(true).start();
      try (InputStream ignored = check.getInputStream()) {
        ignored.readAllBytes();
      }
      boolean exited = check.waitFor(10, TimeUnit.SECONDS);
      if (!exited || check.exitValue() != 0) {
        throw new IllegalStateException(
            "ffmpeg is not available (binary '" + ffmpegBinary + "' failed its version check)");
      }
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      if (check != null) {
        check.destroyForcibly();
      }
      throw new IllegalStateException(
          "ffmpeg is not available (binary '" + ffmpegBinary + "'): " + failure.getMessage(),
          failure);
    }
  }

  private int[] readDesktopSize(InputStream stderr) {
    CountDownLatch found = new CountDownLatch(1);
    AtomicReference<int[]> size = new AtomicReference<>(null);
    StringBuilder head = new StringBuilder();
    Thread gobbler =
        new Thread(
            () -> {
              // Drain stderr for the life of the process (an undrained pipe would stall
              // ffmpeg), latching as soon as the input dimensions arrive.
              try (java.io.BufferedReader lines =
                  new java.io.BufferedReader(new java.io.InputStreamReader(stderr))) {
                String line;
                while ((line = lines.readLine()) != null) {
                  synchronized (head) {
                    if (head.length() < 2000) {
                      head.append(line).append('\n');
                    }
                  }
                  if (size.get() == null && line.contains("Video:")) {
                    // The line can carry hex constants such as 0x524742 before the real
                    // dimensions, so every candidate pair is validated, not just the first.
                    Matcher matcher = VIDEO_DIMENSIONS.matcher(line);
                    while (size.get() == null && matcher.find()) {
                      int desktopWidth = Integer.parseInt(matcher.group(1));
                      int desktopHeight = Integer.parseInt(matcher.group(2));
                      if (desktopWidth > 0
                          && desktopHeight > 0
                          && desktopWidth <= 16384
                          && desktopHeight <= 16384) {
                        size.set(new int[] {desktopWidth, desktopHeight});
                        found.countDown();
                      }
                    }
                  }
                }
              } catch (IOException ignored) {
                // Falls through to the timeout failure below.
              } finally {
                found.countDown();
              }
            },
            "X11DesktopSizeProbe");
    gobbler.setDaemon(true);
    gobbler.start();
    // The size line arrives once ffmpeg opens the display; afterwards stderr stays quiet, so the
    // gobbler only terminates when the process ends and is left draining in the background.
    try {
      if (!found.await(START_TIMEOUT.getSeconds(), TimeUnit.SECONDS) || size.get() == null) {
        String said;
        synchronized (head) {
          said = head.toString();
        }
        throw new IllegalStateException(
            "Could not determine the desktop size on display "
                + display
                + " from the capture pipeline; pipeline said: "
                + (said.isBlank() ? "<no stderr output>" : said.trim()));
      }
      return size.get();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while probing the desktop size", failure);
    }
  }

  private void readLoop() {
    InputStream stdout = ffmpeg.getInputStream();
    try {
      while (!closed.get()) {
        byte[] raw;
        try {
          raw = stdout.readNBytes(frameBytes);
        } catch (IOException failure) {
          if (!closed.get()) {
            fail("desktop capture pipeline broke: " + failure.getMessage());
          }
          return;
        }
        if (raw.length < frameBytes) {
          if (!closed.get()) {
            fail("desktop capture pipeline ended (short frame)");
          }
          return;
        }
        latest.set(new Slot(toImage(raw), Instant.now()));
      }
    } catch (RuntimeException failure) {
      if (!closed.get()) {
        fail("desktop capture reader failed: " + failure.getMessage());
      }
    }
  }

  private BufferedImage toImage(byte[] rgb) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    for (int i = 0, n = width * height; i < n; i++) {
      bgr[3 * i] = rgb[3 * i + 2];
      bgr[3 * i + 1] = rgb[3 * i + 1];
      bgr[3 * i + 2] = rgb[3 * i];
    }
    return image;
  }

  private void fail(String message) {
    if (failure.compareAndSet(null, message)) {
      logger.warn("View-only desktop capture failed: {}", message);
    }
  }

  @Override
  public CapturedFrame captureFrame() {
    if (closed.get()) {
      throw new IllegalStateException("View-only desktop capture is closed");
    }
    String terminal = failure.get();
    if (terminal != null) {
      throw new IllegalStateException(terminal);
    }
    Slot slot = latest.get();
    if (slot == null) {
      throw new IllegalStateException("No desktop frame has arrived yet on display " + display);
    }
    Duration age = Duration.between(slot.arrivedAt(), Instant.now());
    Duration staleAfter = Duration.ofSeconds(Math.max(10, 5L / Math.max(1, framesPerSecond)));
    if (age.compareTo(staleAfter) > 0) {
      throw new IllegalStateException(
          "Desktop frames on display "
              + display
              + " are stale (last frame "
              + age.toSeconds()
              + "s ago)");
    }
    long delivered = deliveredCount.incrementAndGet();
    BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    Graphics graphics = copy.getGraphics();
    graphics.drawImage(slot.image(), 0, 0, null);
    graphics.dispose();
    return new CapturedFrame(
        copy,
        delivered == 1 ? FrameFreshness.NEW : FrameFreshness.CHANGED,
        Instant.now(),
        slot.arrivedAt(),
        identity,
        0);
  }

  @Override
  public ProcessIdentity identity() {
    return identity;
  }

  @Override
  public long selectionGeneration() {
    return 0;
  }

  @Override
  public Rectangle targetBounds() {
    return new Rectangle(0, 0, width, height);
  }

  /** Returns how many frames this source has delivered. */
  public long getDeliveredCount() {
    return deliveredCount.get();
  }

  /** Returns the terminal failure message, or null while the pipeline is healthy. */
  public String getFailure() {
    return failure.get();
  }

  /** Returns when the pipeline was started. */
  public Instant getStartedAt() {
    return startedAt;
  }

  /** Returns the observed display. */
  public String getDisplay() {
    return display;
  }

  /** Returns the observation rate. */
  public int getFramesPerSecond() {
    return framesPerSecond;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    ffmpeg.destroy();
    try {
      if (!ffmpeg.waitFor(3, TimeUnit.SECONDS)) {
        ffmpeg.destroyForcibly();
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      ffmpeg.destroyForcibly();
    }
    reader.interrupt();
    logger.info("View-only desktop capture stopped on display {}", display);
  }
}
