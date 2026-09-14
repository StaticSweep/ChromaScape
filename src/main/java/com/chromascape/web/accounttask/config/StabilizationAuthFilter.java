package com.chromascape.web.accounttask.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization guard for the stabilization surface, implemented as a {@link OncePerRequestFilter}
 * so authentication always runs before any multipart parsing.
 *
 * <p>The read token authorizes GET requests; the write token authorizes every POST (complete report
 * ingestion included). Tokenless access is permitted only when the active profile is exactly {@code
 * local-development} and the direct peer is loopback; forwarded address headers are never trusted
 * without an explicit trusted-proxy configuration. Outside {@code local-development}, application
 * startup fails when either token is blank.
 *
 * <p>After authorization succeeds, non-multipart request bodies are wrapped in a bounded {@link
 * ServletInputStream} that enforces {@code stabilization.auth.max-json-bytes} for both known-length
 * and chunked requests, throwing a mapped 413. Multipart report bundles are sized by the bundle
 * reader's own part/total limits, never by this filter.
 */
@Component
public class StabilizationAuthFilter extends OncePerRequestFilter {

  private static final String AUTH_HEADER = "X-Stabilization-Auth";
  private static final long DEFAULT_MAX_JSON_BYTES = 5_000_000L;

  private final String authToken;
  private final String writeToken;
  private final long maxJsonBytes;
  private final boolean localDevelopment;

  @org.springframework.beans.factory.annotation.Autowired
  public StabilizationAuthFilter(
      @Value("${stabilization.auth.token:}") String authToken,
      @Value("${stabilization.auth.write-token:}") String writeToken,
      @Value("${stabilization.auth.max-json-bytes:5000000}") long maxJsonBytes,
      Environment environment) {
    this.authToken = authToken == null ? "" : authToken.trim();
    this.writeToken = writeToken == null ? "" : writeToken.trim();
    this.maxJsonBytes = maxJsonBytes <= 0 ? DEFAULT_MAX_JSON_BYTES : maxJsonBytes;
    // The tokenless fallback is enabled only when local-development is the SOLE active profile;
    // a mixed production/local profile set never grants tokenless access.
    java.util.List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
    this.localDevelopment =
        activeProfiles.size() == 1 && "local-development".equals(activeProfiles.get(0));
    if (!localDevelopment && (this.authToken.isBlank() || this.writeToken.isBlank())) {
      throw new IllegalStateException(
          "stabilization read and write tokens are required outside local-development");
    }
  }

  /** Test constructor: explicitly local-development so tokenless loopback fallback is active. */
  public StabilizationAuthFilter(String authToken, String writeToken, long maxJsonBytes) {
    this(authToken, writeToken, maxJsonBytes, localDevelopmentEnvironment());
  }

  private static Environment localDevelopmentEnvironment() {
    org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    environment.setActiveProfiles("local-development");
    return environment;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String supplied = request.getHeader(AUTH_HEADER);
    boolean writeRequest = isWriteRequest(request);
    String requiredToken = writeRequest ? writeToken : authToken;

    if (!requiredToken.isBlank()) {
      if (constantTimeEquals(requiredToken, supplied)) {
        doFilterAuthorized(request, response, filterChain);
        return;
      }
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return;
    }
    // No token configured: only the explicit local-development profile receives this fallback.
    if (!localDevelopment) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized_environment\"}");
      return;
    }
    if (isLoopback(request.getRemoteAddr())) {
      doFilterAuthorized(request, response, filterChain);
      return;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"unauthorized_environment\"}");
  }

  /**
   * Runs the chain with a bounded input stream installed for non-multipart bodies. The wrapper is
   * installed only after authorization succeeds; a known-length body beyond the JSON bound is
   * rejected with 413 immediately, and a chunked body is bounded while it is read.
   */
  private void doFilterAuthorized(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!isMultipart(request) && request.getContentLengthLong() > maxJsonBytes) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"request_body_too_large\"}");
      return;
    }
    HttpServletRequest effective = request;
    if (!isMultipart(request)) {
      effective = new BoundedRequestWrapper(request, maxJsonBytes);
    }
    try {
      filterChain.doFilter(effective, response);
    } catch (RequestBodyTooLargeException e) {
      if (!response.isCommitted()) {
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"request_body_too_large\"}");
      } else {
        throw e;
      }
    }
  }

  /** Raised by the bounded input stream when the JSON body exceeds the configured bound. */
  static final class RequestBodyTooLargeException extends RuntimeException {
    RequestBodyTooLargeException(String message) {
      super(message);
    }
  }

  /** Wraps the servlet input stream so both known-length and chunked bodies are bounded. */
  private static final class BoundedRequestWrapper extends HttpServletRequestWrapper {
    private final long maxBytes;
    // One wrapper and one counter per request: repeated getInputStream() calls must observe the
    // same byte counter and must never reset the bound.
    private final BoundedServletInputStream boundedStream;

    BoundedRequestWrapper(HttpServletRequest request, long maxBytes) throws IOException {
      super(request);
      this.maxBytes = maxBytes;
      this.boundedStream =
          new BoundedServletInputStream(
              ((HttpServletRequest) getRequest()).getInputStream(), maxBytes);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return boundedStream;
    }

    @Override
    public int getContentLength() {
      return -1;
    }

    @Override
    public long getContentLengthLong() {
      return -1L;
    }
  }

  /** Counts bytes read and fails with a mapped 413 when the bound is exceeded. */
  private static final class BoundedServletInputStream extends ServletInputStream {
    private final InputStream delegate;
    private final long maxBytes;
    private long count;
    private boolean atEof;

    BoundedServletInputStream(InputStream delegate, long maxBytes) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      if (atEof) {
        return -1;
      }
      int value = delegate.read();
      if (value == -1) {
        atEof = true;
        return -1;
      }
      count++;
      if (count > maxBytes) {
        throw new RequestBodyTooLargeException(
            "request body exceeds the " + maxBytes + " byte JSON bound");
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (atEof) {
        return -1;
      }
      int read = delegate.read(bytes, offset, length);
      if (read < 0) {
        atEof = true;
        return -1;
      }
      count += read;
      if (count > maxBytes) {
        throw new RequestBodyTooLargeException(
            "request body exceeds the " + maxBytes + " byte JSON bound");
      }
      return read;
    }

    @Override
    public boolean isFinished() {
      // isFinished() must reflect EOF, never quota exhaustion: a stream that hit the bound but has
      // not reached end-of-body must not report finished.
      return atEof;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      // synchronous reads only
    }
  }

  /**
   * True for multipart/form-data bodies (report bundles), matched on the exact normalized base type
   * so substring tricks can never bypass the multipart sizing path.
   */
  private static boolean isMultipart(HttpServletRequest request) {
    String contentType = request.getContentType();
    if (contentType == null) {
      return false;
    }
    int semicolon = contentType.indexOf(';');
    String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return "multipart/form-data".equals(base.trim().toLowerCase(java.util.Locale.ENGLISH));
  }

  /** Constant-time token comparison; a null/blank supplied value never matches. */
  private static boolean constantTimeEquals(String expected, String supplied) {
    if (supplied == null || supplied.isBlank()) {
      return false;
    }
    byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] suppliedBytes = supplied.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (expectedBytes.length != suppliedBytes.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < expectedBytes.length; i++) {
      result |= expectedBytes[i] ^ suppliedBytes[i];
    }
    return result == 0;
  }

  /** Every POST mutates state, including complete report ingestion. */
  private static boolean isWriteRequest(HttpServletRequest request) {
    String method = request.getMethod();
    return method != null && "POST".equalsIgnoreCase(method);
  }

  private static boolean isLoopback(String address) {
    if (address == null) {
      return false;
    }
    if ("localhost".equalsIgnoreCase(address)
        || "::1".equals(address)
        || "0:0:0:0:0:0:0:1".equals(address)) {
      return true;
    }
    String[] octets = address.split("\\.");
    if (octets.length != 4 || !"127".equals(octets[0])) {
      return false;
    }
    return Arrays.stream(octets).skip(1).allMatch(o -> o.matches("\\d{1,3}"));
  }
}
