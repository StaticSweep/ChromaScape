package com.chromascape.web.debug.buildergoal.recovery.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses bounded sanitized Gradle/Javac/Kotlin compiler output into structured diagnostics with
 * repository-relative paths. The raw artifact is retained separately and unchanged.
 */
public final class BuilderGoalCompileDiagnosticParser {

  public record Diagnostic(
      String tool,
      String severity,
      String relativeFile,
      int line,
      Integer column,
      String code,
      String message) {}

  public record ParseResult(List<Diagnostic> diagnostics, int errorCount, int warningCount) {}

  private static final int MAX_LINE_LENGTH = 16 * 1024;

  private static final Pattern JAVAC_PATTERN =
      Pattern.compile("^(.+?):(\\d+):(?:(\\d+):)?\\s*(error|warning)?:?\\s*(.*)$");
  private static final Pattern KOTLIN_PATTERN =
      Pattern.compile("^(.+?):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(error|warning):\\s*(.*)$");
  private static final Pattern GRADLE_TASK_FAILED_PATTERN =
      Pattern.compile("^>\\s*Task\\s+(\\S+)\\s+FAILED");
  private static final Pattern DRIVE_LETTER_PATTERN = Pattern.compile("^[A-Za-z]:");

  private BuilderGoalCompileDiagnosticParser() {}

  public static ParseResult parse(List<String> lines, int maxDiagnostics, String repositoryRoot) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    int errors = 0;
    int warnings = 0;
    for (String rawLine : lines) {
      if (diagnostics.size() >= maxDiagnostics) {
        break;
      }
      String line = truncate(rawLine);
      if (line == null || line.isBlank()) {
        continue;
      }
      Matcher gradle = GRADLE_TASK_FAILED_PATTERN.matcher(line);
      if (gradle.matches()) {
        diagnostics.add(
            new Diagnostic(
                "gradle",
                "error",
                null,
                0,
                null,
                "GRADLE_TASK_FAILED",
                gradle.group(1) + " FAILED"));
        errors++;
        continue;
      }
      Matcher kotlin = KOTLIN_PATTERN.matcher(line);
      if (kotlin.matches()) {
        String severity = kotlin.group(4).toLowerCase(Locale.ROOT);
        diagnostics.add(
            new Diagnostic(
                "kotlin",
                severity,
                normalizePath(kotlin.group(1), repositoryRoot),
                Integer.parseInt(kotlin.group(2)),
                Integer.parseInt(kotlin.group(3)),
                null,
                kotlin.group(5)));
        if ("error".equals(severity)) {
          errors++;
        } else {
          warnings++;
        }
        continue;
      }
      Matcher javac = JAVAC_PATTERN.matcher(line);
      if (javac.matches() && javac.group(4) != null) {
        String severity = javac.group(4).toLowerCase(Locale.ROOT);
        diagnostics.add(
            new Diagnostic(
                "javac",
                severity,
                normalizePath(javac.group(1), repositoryRoot),
                Integer.parseInt(javac.group(2)),
                javac.group(3) == null ? null : Integer.parseInt(javac.group(3)),
                null,
                javac.group(5)));
        if ("error".equals(severity)) {
          errors++;
        } else {
          warnings++;
        }
      }
    }
    return new ParseResult(List.copyOf(diagnostics), errors, warnings);
  }

  /**
   * Normalizes absolute Windows or Unix paths to repository-relative paths. Windows drive letters
   * and backslashes are normalized first; a repository root prefix is stripped when present.
   */
  static String normalizePath(String rawPath, String repositoryRoot) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    String path = DRIVE_LETTER_PATTERN.matcher(rawPath).replaceFirst("");
    path = path.replace('\\', '/');
    while (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (repositoryRoot != null && !repositoryRoot.isBlank()) {
      String root = DRIVE_LETTER_PATTERN.matcher(repositoryRoot).replaceFirst("");
      root = root.replace('\\', '/');
      while (root.startsWith("/")) {
        root = root.substring(1);
      }
      while (root.endsWith("/")) {
        root = root.substring(0, root.length() - 1);
      }
      if (!root.isEmpty() && path.startsWith(root + "/")) {
        path = path.substring(root.length() + 1);
      }
    }
    return path;
  }

  static String truncate(String line) {
    if (line == null) {
      return null;
    }
    return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH);
  }
}
