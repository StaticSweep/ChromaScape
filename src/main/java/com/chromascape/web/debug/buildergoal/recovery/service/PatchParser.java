package com.chromascape.web.debug.buildergoal.recovery.service;

import com.chromascape.web.debug.buildergoal.recovery.RecoveryException;
import com.chromascape.web.debug.buildergoal.recovery.RecoveryFailureCodes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates canonical unified diffs for Builder Goal recovery proposals. Only strict
 * git-format diffs with repository-relative forward-slash paths are accepted; malformed, oversized,
 * overlapping, binary, or traversal-carrying patches fail closed.
 */
public final class PatchParser {

  public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {}

  public record PatchFile(
      String path,
      String oldPath,
      String changeType,
      List<Hunk> hunks,
      List<String> oldLines,
      List<String> newLines) {}

  public record ParsedPatch(List<PatchFile> files, int totalBytes, int totalLines) {
    public Set<String> affectedPaths() {
      Set<String> paths = new LinkedHashSet<>();
      for (PatchFile file : files) {
        paths.add(file.path());
        if (file.oldPath() != null) {
          paths.add(file.oldPath());
        }
      }
      return paths;
    }
  }

  private static final Pattern FILE_HEADER_PATTERN = Pattern.compile("^diff --git a/(.+) b/(.+)$");
  private static final Pattern OLD_PATH_PATTERN = Pattern.compile("^--- (?:a/(.+)|/dev/null)$");
  private static final Pattern NEW_PATH_PATTERN =
      Pattern.compile("^\\+\\+\\+ (?:b/(.+)|/dev/null)$");
  private static final Pattern HUNK_HEADER_PATTERN =
      Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
  private static final Pattern NEW_FILE_PATTERN = Pattern.compile("^new file mode .*$");
  private static final Pattern DELETED_FILE_PATTERN = Pattern.compile("^deleted file mode .*$");
  private static final Pattern RENAME_PATTERN = Pattern.compile("^(?:rename|copy) (?:from|to) .*$");

  private PatchParser() {}

  public static ParsedPatch parse(String diff, String repositoryRoot) {
    if (diff == null || diff.isBlank()) {
      throw patchInvalid("Patch is empty");
    }
    if (diff.contains("GIT binary patch") || diff.contains("Binary files")) {
      throw patchInvalid("Binary patches are not supported");
    }
    String normalized = diff.replace("\r\n", "\n");
    String[] lines = normalized.split("\n", -1);
    List<PatchFile> files = new ArrayList<>();
    int index = 0;
    int totalLines = 0;
    while (index < lines.length) {
      String line = lines[index];
      if (line.isBlank()) {
        index++;
        continue;
      }
      Matcher header = FILE_HEADER_PATTERN.matcher(line);
      if (!header.matches()) {
        throw patchInvalid("Expected 'diff --git a/... b/...' header");
      }
      String headerA = header.group(1);
      String headerB = header.group(2);
      index++;
      boolean newFile = false;
      boolean deletedFile = false;
      String renameOrCopy = null;
      String oldPath = null;
      String newPath = null;
      List<Hunk> hunks = new ArrayList<>();
      while (index < lines.length) {
        String content = lines[index];
        if (content.isBlank()) {
          index++;
          continue;
        }
        if (content.startsWith("diff --git ")) {
          break;
        }
        if (NEW_FILE_PATTERN.matcher(content).matches()) {
          newFile = true;
        } else if (DELETED_FILE_PATTERN.matcher(content).matches()) {
          deletedFile = true;
        } else if (RENAME_PATTERN.matcher(content).matches()) {
          renameOrCopy = content;
        } else {
          Matcher oldMatcher = OLD_PATH_PATTERN.matcher(content);
          if (oldMatcher.matches()) {
            oldPath = oldMatcher.group(1);
            index++;
            String next = index < lines.length ? lines[index] : "";
            Matcher newMatcher = NEW_PATH_PATTERN.matcher(next);
            if (newMatcher.matches()) {
              newPath = newMatcher.group(1);
              index++;
            }
            continue;
          }
          Matcher hunkMatcher = HUNK_HEADER_PATTERN.matcher(content);
          if (hunkMatcher.matches()) {
            HunkResult hunk = readHunk(lines, index, hunkMatcher);
            hunks.add(hunk.hunk());
            totalLines += hunk.lines().size();
            index = hunk.endIndex();
            continue;
          }
          if (content.startsWith("index ")
              || content.startsWith("similarity index ")
              || content.startsWith("old mode ")
              || content.startsWith("new mode ")) {
            index++;
            continue;
          }
          throw patchInvalid("Unexpected content inside patch: " + describe(content));
        }
        index++;
      }
      validatePath(headerA);
      validatePath(headerB);
      String path = newFile ? headerB : deletedFile ? headerA : headerB;
      String effectiveOldPath = deletedFile || !headerA.equals(headerB) ? headerA : null;
      if (renameOrCopy != null) {
        throw patchInvalid("Rename and copy operations are not allowed in this policy");
      }
      if (hunks.isEmpty()) {
        throw patchInvalid("Patch file has no hunks: " + path);
      }
      if (newFile && (oldPath != null || deletedFile)) {
        throw patchInvalid("New-file patch has inconsistent headers");
      }
      List<String> oldLines = extractLines(hunks, true);
      List<String> newLines = extractLines(hunks, false);
      files.add(
          new PatchFile(
              path,
              effectiveOldPath,
              newFile ? "ADD" : deletedFile ? "DELETE" : "MODIFY",
              hunks,
              oldLines,
              newLines));
    }
    if (files.isEmpty()) {
      throw patchInvalid("Patch contains no files");
    }
    return new ParsedPatch(List.copyOf(files), diff.length(), totalLines);
  }

  private record HunkResult(Hunk hunk, int endIndex, List<String> lines) {}

  private static HunkResult readHunk(String[] lines, int startIndex, Matcher header) {
    int oldStart = Integer.parseInt(header.group(1));
    int oldCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
    int newStart = Integer.parseInt(header.group(3));
    int newCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
    List<String> hunkLines = new ArrayList<>();
    int index = startIndex + 1;
    int seenOld = 0;
    int seenNew = 0;
    while (seenOld < oldCount || seenNew < newCount) {
      if (index >= lines.length) {
        throw patchInvalid("Truncated hunk");
      }
      String line = lines[index];
      if (line.startsWith("\\")) {
        hunkLines.add(line);
        index++;
        continue;
      }
      if (line.isEmpty()) {
        hunkLines.add(" ");
        seenOld++;
        seenNew++;
        index++;
        continue;
      }
      char marker = line.charAt(0);
      if (marker == ' ') {
        hunkLines.add(line);
        seenOld++;
        seenNew++;
        index++;
      } else if (marker == '-') {
        hunkLines.add(line);
        seenOld++;
        index++;
      } else if (marker == '+') {
        hunkLines.add(line);
        seenNew++;
        index++;
      } else {
        throw patchInvalid("Malformed hunk line");
      }
    }
    if (index < lines.length) {
      String next = lines[index];
      if (next.startsWith(" ") || next.startsWith("-") || next.startsWith("+")) {
        throw patchInvalid("Hunk line counts do not match header");
      }
    }
    return new HunkResult(
        new Hunk(oldStart, oldCount, newStart, newCount, List.copyOf(hunkLines)), index, hunkLines);
  }

  private static List<String> extractLines(List<Hunk> hunks, boolean oldSide) {
    List<String> result = new ArrayList<>();
    for (Hunk hunk : hunks) {
      for (String line : hunk.lines()) {
        if (line.startsWith("\\")) {
          continue;
        }
        char marker = line.charAt(0);
        if (marker == ' ') {
          result.add(line.substring(1));
        } else if (oldSide && marker == '-') {
          result.add(line.substring(1));
        } else if (!oldSide && marker == '+') {
          result.add(line.substring(1));
        }
      }
    }
    return result;
  }

  static void validatePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      throw patchInvalid("Patch path is missing");
    }
    String path = rawPath;
    if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2) {
      throw patchInvalid("Quoted non-ASCII paths are not supported");
    }
    if (path.startsWith("//") || path.startsWith("\\\\")) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, "UNC paths are not allowed in patches");
    }
    if (path.matches("^[A-Za-z]:.*")) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.PATCH_PATH_FORBIDDEN,
          "Absolute Windows paths are not allowed in patches");
    }
    if (path.startsWith("/")) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, "Absolute paths are not allowed in patches");
    }
    String normalized = path.replace('\\', '/');
    String[] segments = normalized.split("/");
    for (String segment : segments) {
      if (segment.isEmpty() || ".".equals(segment)) {
        throw patchInvalid("Patch path contains empty or dot segments");
      }
      if ("..".equals(segment)) {
        throw RecoveryException.unprocessable(
            RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, "Path traversal is not allowed in patches");
      }
    }
    if (normalized.toLowerCase(Locale.ROOT).endsWith(".jks")
        || normalized.toLowerCase(Locale.ROOT).endsWith(".keystore")
        || normalized.toLowerCase(Locale.ROOT).contains(".git/")) {
      throw RecoveryException.unprocessable(
          RecoveryFailureCodes.PATCH_PATH_FORBIDDEN, "Protected paths are not allowed in patches");
    }
  }

  private static com.chromascape.web.debug.buildergoal.recovery.RecoveryException patchInvalid(
      String message) {
    return com.chromascape.web.debug.buildergoal.recovery.RecoveryException.unprocessable(
        RecoveryFailureCodes.PATCH_FORMAT_INVALID, message);
  }

  private static String describe(String content) {
    return content.length() > 60 ? content.substring(0, 60) + "..." : content;
  }
}
