package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/** Creates reviewed patch artifacts in an isolated clone without changing the live Micro6 tree. */
@Service
public class BuilderGoalRepairPatchExportService {

  private static final String ALLOWED_SOURCE_PREFIX =
      "runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/tasks/";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");
  private static final List<String> FORBIDDEN_ADDITIONS =
      List.of(
          "Runtime.getRuntime().exec", "ProcessBuilder", "System.exit", "java.io.FilePermission");

  private final PatchLoopIterationRepository iterationRepository;
  private final BuilderGoalRunStateMutationService mutationService;
  private final ObjectMapper objectMapper;
  private final Path sourceRoot;
  private final Path repairRoot;

  public BuilderGoalRepairPatchExportService(
      PatchLoopIterationRepository iterationRepository,
      BuilderGoalRunStateMutationService mutationService,
      ObjectMapper objectMapper,
      @Value("${builder-goal.recovery.micro6-project-dir}") String sourceRoot,
      @Value("${builder-goal.repair.workspace-root:${user.home}/.chromascape/repair-workspaces}")
          String repairRoot) {
    this.iterationRepository = iterationRepository;
    this.mutationService = mutationService;
    this.objectMapper = objectMapper;
    this.sourceRoot = Path.of(sourceRoot).toAbsolutePath().normalize();
    this.repairRoot = Path.of(repairRoot).toAbsolutePath().normalize();
  }

  public PatchExportResult export(String iterationId) throws Exception {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationId(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));
    requireIncidentPatchReview(iteration);
    AnalysisV2PatchDraft draft = readPatchDraft(iteration);
    List<AnalysisV2PatchDraft.PatchChange> changes = draft.getChanges();
    if (changes == null || changes.isEmpty()) {
      throw new IllegalStateException("The reviewed analysis does not contain a patch draft");
    }

    Path liveRoot = sourceRoot.toRealPath();
    Files.createDirectories(repairRoot);
    Path managedRoot = repairRoot.toRealPath();
    if (liveRoot.equals(managedRoot) || managedRoot.startsWith(liveRoot)) {
      throw new IllegalStateException(
          "Repair workspace must be outside the live Micro6 repository");
    }

    Set<String> relativeFiles = validateChanges(liveRoot, changes);
    String artifactId =
        sanitize(iterationId)
            + "-"
            + sanitize(firstNonBlank(draft.getPatchId(), "repair"))
            + "-"
            + System.currentTimeMillis();
    Path workspace = managedRoot.resolve("workspaces").resolve(artifactId);
    Path artifactDirectory = managedRoot.resolve("artifacts").resolve(artifactId);
    if (Files.exists(workspace) || Files.exists(artifactDirectory)) {
      throw new IllegalStateException("Repair artifact workspace already exists");
    }
    Files.createDirectories(workspace.getParent());
    Files.createDirectories(artifactDirectory);

    runGit(List.of("clone", "--no-hardlinks", liveRoot.toString(), workspace.toString()), liveRoot);
    for (String relative : relativeFiles) {
      Path source = resolveContainedFile(liveRoot, relative);
      Path target = resolveManagedCloneTarget(workspace, relative, false);
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
    runGit(withPaths(List.of("add", "--"), relativeFiles), workspace);

    applyChanges(workspace, changes);
    String diff =
        runGitCapture(
            withPaths(List.of("diff", "--no-ext-diff", "--binary", "--"), relativeFiles),
            workspace);
    if (diff.isBlank()) {
      throw new IllegalStateException("The reviewed patch produced no source changes");
    }
    rejectForbiddenAdditions(diff);

    DiffSize size = readDiffSize(workspace, relativeFiles);
    if (size.filesChanged > 5 || size.linesChanged > 300) {
      throw new IllegalStateException(
          "Repair patch exceeds policy (max 5 files and 300 changed lines)");
    }

    Path patchFile = artifactDirectory.resolve("repair.patch");
    Files.writeString(patchFile, diff, StandardCharsets.UTF_8);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("artifact_id", artifactId);
    metadata.put("iteration_id", iterationId);
    metadata.put("patch_id", draft.getPatchId());
    metadata.put("created_at", Instant.now().toString());
    metadata.put("affected_files", new ArrayList<>(relativeFiles));
    metadata.put("files_changed", size.filesChanged);
    metadata.put("lines_changed", size.linesChanged);
    metadata.put("workspace", workspace.toString());
    metadata.put("patch_file", patchFile.toString());
    Files.writeString(
        artifactDirectory.resolve("metadata.json"),
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
        StandardCharsets.UTF_8);
    mutationService.recordRepairPatchExport(iterationId, metadata);

    return new PatchExportResult(
        artifactId,
        "repair.patch",
        new ArrayList<>(relativeFiles),
        size.filesChanged,
        size.linesChanged,
        "/api/debug/builder-goals/repair-patches/" + artifactId);
  }

  public Resource load(String artifactId) throws IOException {
    if (artifactId == null || !SAFE_ID.matcher(artifactId).matches()) {
      throw new IllegalArgumentException("Invalid repair patch artifact id");
    }
    Path root = repairRoot.toRealPath();
    Path patch = root.resolve("artifacts").resolve(artifactId).resolve("repair.patch").normalize();
    if (!patch.startsWith(root) || !Files.isRegularFile(patch)) {
      throw new IllegalArgumentException("Repair patch artifact not found: " + artifactId);
    }
    Path realPatch = patch.toRealPath();
    if (!realPatch.startsWith(root)) {
      throw new IllegalArgumentException("Repair patch artifact escapes the managed directory");
    }
    return new FileSystemResource(realPatch);
  }

  private AnalysisV2PatchDraft readPatchDraft(PatchLoopIteration iteration) {
    JsonNode response = iteration.getAnalysisResponseJson();
    if (response == null || response.isNull()) {
      throw new IllegalStateException("Generate and review a patch plan before exporting");
    }
    JsonNode patchDraft = response.path("patchDraft");
    if (patchDraft.isMissingNode() || patchDraft.isNull()) {
      patchDraft = response.path("patch_draft");
    }
    if (patchDraft.isMissingNode() || patchDraft.isNull()) {
      throw new IllegalStateException("The analysis response does not contain a patch draft");
    }
    return objectMapper.convertValue(patchDraft, AnalysisV2PatchDraft.class);
  }

  private void requireIncidentPatchReview(PatchLoopIteration iteration) {
    JsonNode taskMetadata = iteration.getTaskMetadataJson();
    JsonNode incident = taskMetadata == null ? null : taskMetadata.path("repair_incident");
    if (incident == null || !incident.isObject() || incident.isEmpty()) {
      throw new IllegalStateException(
          "Repair patch export is only available for an auto-detected repair incident");
    }
    String recoveryPhase = taskMetadata.path("recovery").path("phase").asText("");
    if (!"PATCH_PLAN_READY".equals(recoveryPhase)) {
      throw new IllegalStateException(
          "Review and generate the incident patch plan before exporting it");
    }
  }

  private Set<String> validateChanges(Path liveRoot, List<AnalysisV2PatchDraft.PatchChange> changes)
      throws IOException {
    Set<String> files = new LinkedHashSet<>();
    for (AnalysisV2PatchDraft.PatchChange change : changes) {
      String relative = normalizeRelative(change.getFilePath());
      if (!relative.startsWith(ALLOWED_SOURCE_PREFIX) || !relative.endsWith(".java")) {
        throw new IllegalArgumentException(
            "Repair change is outside Builder Goal task sources: " + relative);
      }
      resolveContainedFile(liveRoot, relative);
      files.add(relative);
    }
    if (files.isEmpty() || files.size() > 5) {
      throw new IllegalArgumentException(
          "Repair patch must change between one and five task source files");
    }
    return files;
  }

  private Path resolveContainedFile(Path root, String relative) throws IOException {
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
      throw new IllegalArgumentException("Repair target does not exist inside Micro6: " + relative);
    }
    Path real = resolved.toRealPath();
    if (!real.startsWith(root)) {
      throw new IllegalArgumentException(
          "Repair target escapes Micro6 through a symbolic link: " + relative);
    }
    return real;
  }

  private void applyChanges(Path workspace, List<AnalysisV2PatchDraft.PatchChange> changes)
      throws IOException {
    for (AnalysisV2PatchDraft.PatchChange change : changes) {
      String relative = normalizeRelative(change.getFilePath());
      Path target = resolveManagedCloneTarget(workspace, relative, true);
      String current = Files.readString(target, StandardCharsets.UTF_8);
      String oldText = change.getOldText() == null ? "" : change.getOldText();
      String newText = change.getNewText() == null ? "" : change.getNewText();
      String updated;
      if (!oldText.isBlank()) {
        int first = current.indexOf(oldText);
        if (first < 0 || current.indexOf(oldText, first + oldText.length()) >= 0) {
          throw new IllegalArgumentException(
              "Patch oldText must match exactly once in " + relative);
        }
        updated =
            current.substring(0, first) + newText + current.substring(first + oldText.length());
      } else if (change.getLineNumber() != null) {
        List<String> lines = new ArrayList<>(List.of(current.split("\\R", -1)));
        int index = Math.max(0, Math.min(lines.size(), change.getLineNumber() - 1));
        lines.add(index, newText);
        updated = String.join(System.lineSeparator(), lines);
      } else {
        throw new IllegalArgumentException(
            "Patch change requires oldText or lineNumber: " + relative);
      }
      Files.writeString(target, updated, StandardCharsets.UTF_8);
    }
  }

  private Path resolveManagedCloneTarget(Path workspace, String relative, boolean requireExisting)
      throws IOException {
    Path realWorkspace = workspace.toRealPath();
    Path target = realWorkspace.resolve(relative).normalize();
    if (!target.startsWith(realWorkspace)) {
      throw new IllegalArgumentException("Invalid managed-clone patch target: " + relative);
    }

    Path current = realWorkspace;
    for (Path segment : realWorkspace.relativize(target.getParent())) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        Path realDirectory = current.toRealPath();
        if (!realDirectory.startsWith(realWorkspace) || !Files.isDirectory(realDirectory)) {
          throw new IllegalArgumentException(
              "Managed-clone patch target escapes through a symbolic link: " + relative);
        }
        current = realDirectory;
      } else {
        Files.createDirectory(current);
      }
    }

    Path containedTarget = current.resolve(target.getFileName());
    if (!Files.exists(containedTarget, LinkOption.NOFOLLOW_LINKS)) {
      if (requireExisting) {
        throw new IllegalArgumentException("Invalid managed-clone patch target: " + relative);
      }
      return containedTarget;
    }
    Path realTarget = containedTarget.toRealPath();
    if (!realTarget.startsWith(realWorkspace) || !Files.isRegularFile(realTarget)) {
      throw new IllegalArgumentException(
          "Managed-clone patch target escapes through a symbolic link: " + relative);
    }
    return realTarget;
  }

  private DiffSize readDiffSize(Path workspace, Set<String> files) throws Exception {
    String output = runGitCapture(withPaths(List.of("diff", "--numstat", "--"), files), workspace);
    int fileCount = 0;
    int lineCount = 0;
    for (String line : output.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      String[] columns = line.split("\\t", 3);
      if (columns.length < 3) {
        continue;
      }
      fileCount++;
      lineCount += parseCount(columns[0]) + parseCount(columns[1]);
    }
    return new DiffSize(fileCount, lineCount);
  }

  private void rejectForbiddenAdditions(String diff) {
    for (String line : diff.split("\\R")) {
      if (!line.startsWith("+") || line.startsWith("+++")) {
        continue;
      }
      for (String forbidden : FORBIDDEN_ADDITIONS) {
        if (line.contains(forbidden)) {
          throw new IllegalArgumentException(
              "Repair patch adds forbidden code pattern: " + forbidden);
        }
      }
    }
  }

  private List<String> withPaths(List<String> prefix, Set<String> files) {
    List<String> command = new ArrayList<>(prefix);
    command.addAll(files);
    return command;
  }

  private void runGit(List<String> arguments, Path workingDirectory) throws Exception {
    runGitCapture(arguments, workingDirectory);
  }

  private String runGitCapture(List<String> arguments, Path workingDirectory) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(arguments);
    Process process =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new IllegalStateException("Git command timed out");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Git command failed: " + output.trim());
    }
    return output;
  }

  private String normalizeRelative(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Patch change is missing filePath");
    }
    String normalized = value.replace('\\', '/');
    Path path = Path.of(normalized).normalize();
    String result = path.toString().replace('\\', '/');
    if (path.isAbsolute() || result.startsWith("../") || result.equals("..")) {
      throw new IllegalArgumentException("Patch path escapes the repository: " + value);
    }
    return result;
  }

  private int parseCount(String value) {
    try {
      return "-".equals(value) ? 0 : Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private String sanitize(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first;
  }

  public record PatchExportResult(
      String artifactId,
      String filename,
      List<String> affectedFiles,
      int filesChanged,
      int linesChanged,
      String downloadUrl) {}

  private record DiffSize(int filesChanged, int linesChanged) {}
}
