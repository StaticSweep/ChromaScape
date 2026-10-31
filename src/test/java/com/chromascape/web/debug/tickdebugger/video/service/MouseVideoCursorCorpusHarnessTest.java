package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * SC-2150 FA12 corpus harness. Opt-in: runs only when {@code SC2150_FA12_CORPUS} names a corpus
 * built by {@code verification-runs/*-fa12-synthetic/tools/build_corpus.py}. For every labelled
 * clip of {@code SC2150_FA12_SPLIT} it builds the frame index with the production ffprobe command,
 * derives the production stride, decodes samples with the production ffmpeg command and runs the
 * production analyzer, then writes raw predictions to {@code SC2150_FA12_OUT} for the external
 * scorer. It asserts no accuracy itself, so thresholds are never tuned inside a test run.
 */
@EnabledIfEnvironmentVariable(named = "SC2150_FA12_CORPUS", matches = ".+")
class MouseVideoCursorCorpusHarnessTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void writesPredictionsForEveryLabelledClip() throws Exception {
    Path corpus = Path.of(System.getenv("SC2150_FA12_CORPUS"));
    String split = System.getenv().getOrDefault("SC2150_FA12_SPLIT", "dev");
    Path out = Path.of(System.getenv().getOrDefault("SC2150_FA12_OUT", "build/sc2150-fa12-out"));
    Files.createDirectories(out);
    List<Path> labels;
    try (Stream<Path> files = Files.list(corpus.resolve(split))) {
      String only = System.getenv().getOrDefault("SC2150_FA12_ONLY", "");
      labels =
          files
              .filter(p -> p.toString().endsWith(".labels.json"))
              .filter(p -> only.isEmpty() || p.getFileName().toString().contains(only))
              .sorted()
              .toList();
    }
    assertThat(labels).isNotEmpty();
    // Optional "i/n" sharding so several JVMs can split one corpus deterministically.
    String[] shard = System.getenv().getOrDefault("SC2150_FA12_SHARD", "0/1").split("/");
    int index = Integer.parseInt(shard[0]);
    int shards = Integer.parseInt(shard[1]);
    for (int position = 0; position < labels.size(); position++) {
      if (position % shards == index) {
        evaluate(corpus, labels.get(position), out);
      }
    }
  }

  private void evaluate(Path corpus, Path labelFile, Path out) throws Exception {
    JsonNode label = MAPPER.readTree(labelFile.toFile());
    String clipId = label.path("clip_id").asText();
    Path media = labelFile.resolveSibling(clipId + ".mp4");
    Path template = corpus.resolve("templates").resolve(label.path("template").asText() + ".png");

    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setVisualCursorHotspotX(label.path("hotspot").path(0).asInt());
    properties.setVisualCursorHotspotY(label.path("hotspot").path(1).asInt());
    // Development-only threshold overrides ("name=value,..."), recorded in each predictions file.
    String overrides = System.getenv().getOrDefault("SC2150_FA12_OVERRIDES", "");
    for (String item : overrides.split(",")) {
      String[] pair = item.split("=");
      if (pair.length != 2) {
        continue;
      }
      double number = Double.parseDouble(pair[1].trim());
      switch (pair[0].trim()) {
        case "startScore" -> properties.setVisualCursorStartScore(number);
        case "minScore" -> properties.setVisualCursorMinScore(number);
        case "minGain" -> properties.setVisualCursorMinGain(number);
        case "ambiguityMargin" -> properties.setVisualCursorAmbiguityMargin(number);
        case "cutSpikeMinDelta" -> properties.setCutSpikeMinDelta(number);
        case "cutSpikeRatio" -> properties.setCutSpikeRatio(number);
        case "sampleMaxWidth" -> properties.setVisualSampleMaxWidth((int) number);
        default -> throw new IllegalArgumentException("Unknown override " + pair[0]);
      }
    }

    MouseVideoFrameIndexService.FrozenFrameIndex index = index(properties, media);
    int step =
        MouseVideoSampleStride.compute(
            index, properties.getVisualSampleFps(), properties.getMaxVisualFrames());
    Path frames = Files.createTempDirectory("sc2150-fa12-" + clipId.hashCode());
    try {
      run(
          MouseVideoAnalysisWorker.samplingCommand(
              properties.getFfmpeg(),
              media,
              frames,
              step,
              properties.getMaxVisualFrames(),
              properties.getVisualSampleMaxWidth()));
      int[] size = displaySize(properties, media);
      long started = System.nanoTime();
      MouseVideoVisualAnalyzer.Result result =
          new MouseVideoVisualAnalyzer(properties)
              .analyze(index.frames(), frames, step, size[0], size[1], 0.0d, template);

      List<Map<String, Object>> cursor = new ArrayList<>();
      List<Long> cuts = new ArrayList<>();
      for (Map<String, Object> observation : result.observations()) {
        Object kind = observation.get("kind");
        if ("CURSOR_VISIBILITY".equals(kind)) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("ordinal", observation.get("frameOrdinal"));
          row.put("ptsTime", observation.get("ptsTime"));
          row.put("visibility", observation.get("visibility"));
          row.put("confidence", observation.get("confidence"));
          row.put("x", observation.get("x"));
          row.put("y", observation.get("y"));
          row.put("evidence", observation.get("evidence"));
          cursor.add(row);
        } else if ("SCENE_CUT".equals(kind)) {
          cuts.add(((Number) observation.get("frameOrdinal")).longValue());
        }
      }
      List<String> sampledPts = new ArrayList<>();
      for (long ordinal = 0; ordinal < index.frames().size(); ordinal += step) {
        sampledPts.add(index.frames().get((int) ordinal).ptsTime());
      }
      Map<String, Object> document = new LinkedHashMap<>();
      document.put("schema", "sc2150-fa12-predictions/v1");
      document.put("clip_id", clipId);
      document.put("algorithm_id", MouseVideoVisualAnalyzer.ALGORITHM_ID);
      document.put("cursor_method", MouseVideoCursorTracker.METHOD);
      document.put("overrides", System.getenv().getOrDefault("SC2150_FA12_OVERRIDES", ""));
      document.put("template", label.path("template").asText());
      document.put("template_sha256", sha256(Files.readAllBytes(template)));
      document.put("media_sha256", sha256(Files.readAllBytes(media)));
      document.put("source_size", List.of(size[0], size[1]));
      document.put("indexed_frames", index.frames().size());
      document.put("index_complete", index.complete());
      document.put("step", step);
      document.put("sampled_frames", result.sampledFrames());
      document.put("cursor_template_used", result.cursorTemplateUsed());
      document.put("analyze_millis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      document.put("cursor_calibration", result.quality().get("cursorCalibration"));
      // Geometric supported-case rule from the known displayed size, never from detector output.
      double analysisFactor =
          Math.min(size[0], properties.getVisualSampleMaxWidth()) / (double) size[0];
      document.put(
          "geometry_resolvable",
          MouseVideoCursorTracker.resolvable(
              javax.imageio.ImageIO.read(template.toFile()),
              label.path("cursor_source_scale").asDouble(1.0d),
              analysisFactor));
      document.put("observations_sha256", sha256(MAPPER.writeValueAsBytes(result.observations())));
      document.put("sampled_pts_time", sampledPts);
      document.put("scene_cut_ordinals", cuts);
      document.put("cursor", cursor);
      Files.write(
          out.resolve(clipId + ".predictions.json"),
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
    } finally {
      try (Stream<Path> files = Files.walk(frames)) {
        files.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
      }
    }
  }

  private static MouseVideoFrameIndexService.FrozenFrameIndex index(
      MouseVideoProperties properties, Path media) throws IOException, InterruptedException {
    List<MouseVideoFrameIndexService.Frame> frames = new ArrayList<>();
    String timeBase = null;
    for (String line :
        run(MouseVideoDerivativeWorker.frameIndexCommand(properties.getFfprobe(), media))) {
      Map<String, String> values = MouseVideoDerivativeWorker.parseCompactLine(line);
      if (values.containsKey("time_base")) {
        timeBase = values.get("time_base").trim();
        continue;
      }
      if (!values.containsKey("key_frame")) {
        continue;
      }
      frames.add(
          new MouseVideoFrameIndexService.Frame(
              MouseVideoDerivativeWorker.firstPresent(values, "best_effort_timestamp", "pts"),
              MouseVideoDerivativeWorker.firstPresent(
                  values, "best_effort_timestamp_time", "pts_time"),
              MouseVideoDerivativeWorker.firstPresent(values, "duration_time", "pkt_duration_time"),
              "1".equals(values.getOrDefault("key_frame", "0").trim())));
    }
    String[] parts = timeBase == null ? null : timeBase.split("/");
    return new MouseVideoFrameIndexService.FrozenFrameIndex(
        "harness",
        "frame-index/v2",
        parts != null && parts.length == 2,
        timeBase,
        parts == null ? null : parts[0],
        parts == null ? null : parts[1],
        List.copyOf(frames));
  }

  private static int[] displaySize(MouseVideoProperties properties, Path media)
      throws IOException, InterruptedException {
    List<String> lines =
        run(
            List.of(
                properties.getFfprobe(),
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height",
                "-of",
                "csv=p=0",
                media.toAbsolutePath().toString()));
    String[] parts = lines.get(0).trim().split(",");
    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
  }

  private static List<String> run(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("Command failed (" + exit + "): " + command.get(0) + " " + errors);
    }
    return lines;
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
