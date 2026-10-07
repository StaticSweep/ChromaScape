package com.chromascape.web.replay.session;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One atomic durable boundary: lifecycle, worker checkpoint, source journal and receipts. */
public class XrspsSessionDocument {
  public XrspsSession.Snapshot lifecycle;
  public JsonNode manifest;
  public JsonNode checkpoint;
  public JsonNode observation;
  public List<String> legalActions = new ArrayList<>();
  public List<XrspsRecordedAction> journal = new ArrayList<>();
  public Map<String, XrspsSessionReceipt> receipts = new LinkedHashMap<>();
  public XrspsActionMismatch mismatch;
}
