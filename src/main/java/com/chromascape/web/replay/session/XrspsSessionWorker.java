package com.chromascape.web.replay.session;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Restores the single committed authoritative world; no listener or alternative simulator. */
@Component
public class XrspsSessionWorker {
  private final ReplayProperties properties;
  private final ObjectMapper mapper;
  private final LinkedHashMap<String, Warm> warm = new LinkedHashMap<>();

  private static final class Warm {
    Boundary boundary;
    JsonNode manifest, checkpoint, observation;
    long epoch, revision;
    boolean borrowed;
  }

  /**
   * A bounded process cache, never an authority: each borrow must match the committed checkpoint.
   */
  public synchronized Boundary openPaced(XrspsSessionDocument doc) {
    String key = doc.lifecycle.id() + ":" + doc.lifecycle.leaseId();
    Warm cached = warm.get(key);
    if (cached != null && cached.borrowed)
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID, "Worker is already borrowed");
    if (cached != null
        && (!Objects.equals(cached.manifest, doc.manifest)
            || !Objects.equals(cached.checkpoint, doc.checkpoint)
            || cached.epoch != doc.lifecycle.epoch()
            || cached.revision != doc.lifecycle.observationRevision())) {
      cached.boundary.process.close();
      warm.remove(key);
      cached = null;
    }
    if (cached == null) {
      while (warm.size() >= 2) {
        var victim = warm.entrySet().stream().filter(e -> !e.getValue().borrowed).findFirst();
        if (victim.isEmpty())
          throw ReplayException.conflict(ReplayErrorCode.RUN_STATE_INVALID, "Worker cache is busy");
        victim.get().getValue().boundary.process.close();
        warm.remove(victim.get().getKey());
      }
      cached = new Warm();
      cached.boundary = open(doc);
      cached.manifest = doc.manifest.deepCopy();
      cached.boundary.warmKey = key;
      warm.put(key, cached);
    } else {
      doc.observation = cached.observation.deepCopy();
    }
    cached.borrowed = true;
    cached.boundary.prepared = null;
    return cached.boundary;
  }

  @PreDestroy
  public synchronized void shutdown() {
    warm.values().forEach(value -> value.boundary.process.close());
    warm.clear();
  }

  public XrspsSessionWorker(ReplayProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
  }

  public Boundary open(XrspsSessionDocument doc) {
    if (!properties.isEnabled())
      throw ReplayException.unavailable(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          "Replay is disabled",
          "Enable replay with a pinned authoritative worker");
    XrspsKernelProcess process = new XrspsKernelProcess(properties.getXrsps(), mapper);
    try {
      process.start();
      Boundary boundary = new Boundary(process);
      JsonNode cap = boundary.call("capabilities", mapper.createObjectNode()).path("capabilities");
      if (!cap.path("certifiable").asBoolean(false)
          || !"XRSPS_AUTHORITATIVE".equals(cap.path("fidelity").asText())
          || cap.path("controlAllowed").asBoolean(true)
          || cap.path("sessionProtocol").path("version").asInt() != 1
          || !"session.apply"
              .equals(cap.path("sessionProtocol").path("atomicActionMethod").asText())) {
        throw ReplayException.unavailable(
            ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
            "Worker does not attest authoritative session execution",
            "Configure the SC-1991 production-service worker");
      }
      boundary.presentationSupported =
          cap.path("presentationProtocol").path("version").asInt() == 1
              && cap.path("presentationProtocol").path("readOnly").asBoolean(false);
      doc.legalActions =
          mapper.convertValue(
              cap.path("supportedActions"),
              mapper.getTypeFactory().constructCollectionType(List.class, String.class));
      JsonNode reset =
          boundary.call("session.reset", mapper.createObjectNode().set("manifest", doc.manifest));
      doc.observation = reset.path("initialObservation");
      if (doc.checkpoint != null) {
        JsonNode restored =
            boundary.call("restore", mapper.createObjectNode().set("snapshot", doc.checkpoint));
        doc.observation = restored.path("observation");
      }
      long expectedTick =
          doc.manifest.path("recording").path("startTick").asLong() + doc.lifecycle.tick();
      if (doc.observation.path("tick").asLong(-1) != expectedTick)
        throw ReplayException.conflict(
            ReplayErrorCode.SNAPSHOT_INCOMPATIBLE,
            "Worker checkpoint tick does not match the session");
      return boundary;
    } catch (RuntimeException e) {
      process.close();
      throw e;
    }
  }

  public final class Boundary implements AutoCloseable {
    private final XrspsKernelProcess process;
    private boolean presentationSupported;
    private String warmKey;
    private XrspsSessionDocument prepared;

    Boundary(XrspsKernelProcess process) {
      this.process = process;
    }

    public JsonNode call(String method, JsonNode params) {
      var response = process.send(method, params);
      if (response.isError()) {
        ReplayErrorCode code;
        try {
          code = ReplayErrorCode.valueOf(response.error().path("code").asText());
        } catch (RuntimeException e) {
          code = ReplayErrorCode.XRSPS_PROTOCOL_ERROR;
        }
        throw ReplayException.invalid(code, "Authoritative worker rejected " + method);
      }
      return response.result();
    }

    public void checkpoint(XrspsSessionDocument doc) {
      doc.checkpoint = call("snapshot", mapper.createObjectNode()).path("snapshot");
      doc.verifiedStates.put(
          doc.lifecycle.tick() + ":" + doc.lifecycle.observationRevision(),
          new com.chromascape.web.replay.projection.CanonicalProjectionService()
              .stateHash(doc.observation));
      while (doc.verifiedStates.size() > 4096)
        doc.verifiedStates.remove(doc.verifiedStates.keySet().iterator().next());
      if (presentationSupported) {
        var params =
            mapper
                .createObjectNode()
                .put("afterSequence", doc.clientSequence)
                .put("limit", 128)
                .put("bootstrap", false);
        var page = call("session.presentation", params);
        if (page.path("resyncRequired").asBoolean()
            || (page.path("frames").isArray()
                && !page.path("frames").isEmpty()
                && page.path("frames").get(page.path("frames").size() - 1).path("sequence").asLong()
                    < page.path("latestSequence").asLong())) doc.clientFrames.clear();
        else for (JsonNode frame : page.path("frames")) doc.clientFrames.add(frame.deepCopy());
        while (doc.clientFrames.size() > 256) doc.clientFrames.remove(0);
        var bootstrap =
            call("session.presentation", mapper.createObjectNode().put("bootstrap", true));
        doc.clientBootstrap = bootstrap.path("frames").get(0);
        doc.clientSequence = bootstrap.path("latestSequence").asLong();
      }
      if (!doc.checkpoint.isObject())
        throw ReplayException.invalid(
            ReplayErrorCode.XRSPS_PROTOCOL_ERROR, "Worker snapshot missing");
      prepared = doc;
    }

    @Override
    public void close() {
      synchronized (XrspsSessionWorker.this) {
        if (warmKey == null) {
          process.close();
          return;
        }
        var cached = warm.get(warmKey);
        if (cached == null || prepared == null) {
          process.close();
          warm.remove(warmKey);
          return;
        }
        // A database rollback leaves an older checkpoint. The next borrow compares it and
        // destroys this candidate, so uncommitted native effects can never leak into a retry.
        cached.checkpoint = prepared.checkpoint.deepCopy();
        cached.observation = prepared.observation.deepCopy();
        cached.epoch = prepared.lifecycle.epoch();
        cached.revision = prepared.lifecycle.observationRevision();
        cached.borrowed = false;
        prepared = null;
      }
    }
  }
}
