package com.chromascape.web.replay.session;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Restores the single committed authoritative world; no listener or alternative simulator. */
@Component
public class XrspsSessionWorker {
  private final ReplayProperties properties;
  private final ObjectMapper mapper;

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
      if (!doc.checkpoint.isObject())
        throw ReplayException.invalid(
            ReplayErrorCode.XRSPS_PROTOCOL_ERROR, "Worker snapshot missing");
    }

    @Override
    public void close() {
      process.close();
    }
  }
}
