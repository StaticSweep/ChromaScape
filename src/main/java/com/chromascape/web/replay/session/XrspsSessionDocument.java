package com.chromascape.web.replay.session;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One atomic durable boundary: lifecycle, worker checkpoint, source journal and receipts. */
public class XrspsSessionDocument {
  public int aiRecoveryCount;
  public XrspsSession.Snapshot lifecycle;
  public JsonNode manifest;
  public JsonNode checkpoint;
  public JsonNode observation;
  public JsonNode clientBootstrap;
  public List<JsonNode> clientFrames = new ArrayList<>();
  public long clientSequence;
  public Map<String, String> verifiedStates = new LinkedHashMap<>();
  public JsonNode aiTask;
  public JsonNode aiProviderSession;
  public JsonNode aiProviderTurn;
  public JsonNode aiDecision;
  public JsonNode aiVerification;
  public String aiCoordinator;
  public String playbackOwner;
  public String playbackLease;
  public List<JsonNode> seekHistory = new ArrayList<>();
  public List<String> retiredLeases = new ArrayList<>();
  public List<String> legalActions = new ArrayList<>();
  public List<XrspsRecordedAction> journal = new ArrayList<>();
  public Map<String, XrspsSessionReceipt> receipts = new LinkedHashMap<>();
  public XrspsActionMismatch mismatch;
}
