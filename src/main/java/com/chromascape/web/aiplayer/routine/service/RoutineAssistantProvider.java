package com.chromascape.web.aiplayer.routine.service;

import java.util.Map;

public interface RoutineAssistantProvider {
  Map<String, Object> propose(String conversationId, String message, Map<String, Object> context);
}
