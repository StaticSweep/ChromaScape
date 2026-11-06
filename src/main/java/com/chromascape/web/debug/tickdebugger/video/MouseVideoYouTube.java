package com.chromascape.web.debug.tickdebugger.video;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict canonicalizer for supported YouTube single-video links. Only reviewed {@code
 * youtube.com/watch?v=…} and {@code youtu.be/…} forms are accepted; userinfo, custom ports,
 * playlists, live streams and every other host or extractor are rejected. The provider ID is
 * validated and the canonical URL is rebuilt from it, so no caller-supplied URL string reaches the
 * acquisition process.
 */
public final class MouseVideoYouTube {

  private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
  private static final Pattern PATH_ID = Pattern.compile("^/([A-Za-z0-9_-]{11})/?$");

  private MouseVideoYouTube() {}

  public record Canonical(String providerId, String canonicalUrl) {}

  public static Optional<Canonical> canonicalize(String url) {
    if (url == null || url.isBlank() || url.length() > 2048) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (URISyntaxException malformed) {
      return Optional.empty();
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return Optional.empty();
    }
    if (uri.getUserInfo() != null || uri.getPort() != -1) {
      return Optional.empty();
    }
    String host = uri.getHost();
    if (host == null) {
      return Optional.empty();
    }
    host = host.toLowerCase(java.util.Locale.ROOT);
    String id = null;
    if ("www.youtube.com".equals(host)
        || "youtube.com".equals(host)
        || "m.youtube.com".equals(host)) {
      if (!"/watch".equals(uri.getPath())) {
        return Optional.empty();
      }
      id = queryParam(uri.getRawQuery(), "v");
    } else if ("youtu.be".equals(host) || "www.youtu.be".equals(host)) {
      Matcher matcher = PATH_ID.matcher(uri.getPath() == null ? "" : uri.getPath());
      if (matcher.matches()) {
        id = matcher.group(1);
      }
    } else {
      return Optional.empty();
    }
    if (id == null || !VIDEO_ID.matcher(id).matches()) {
      return Optional.empty();
    }
    return Optional.of(new Canonical(id, "https://www.youtube.com/watch?v=" + id));
  }

  private static String queryParam(String rawQuery, String name) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return null;
    }
    for (String pair : rawQuery.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      if (name.equals(key)) {
        return equals < 0 ? "" : pair.substring(equals + 1);
      }
    }
    return null;
  }
}
