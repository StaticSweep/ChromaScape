package com.chromascape.web.aiplayer.routine.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeterministicRoutineAssistantProvider implements RoutineAssistantProvider {
  @Override public Map<String,Object> propose(String conversationId, String message, Map<String,Object> context) {
    Map<String,Object> diff=new LinkedHashMap<>(); diff.put("schema","behavior-graph-diff/v1"); diff.put("base_graph_id",context==null?null:context.get("graph_id")); diff.put("operations",List.of()); diff.put("reason",message==null?"deterministic review":message); diff.put("authority",Map.of("can_write_source",false,"can_approve_patch",false,"can_publish_graph",false,"can_activate_model",false,"can_dispatch_input",false));
    Map<String,Object> result=new LinkedHashMap<>(); result.put("proposal_id","proposal_"+conversationId); result.put("diff",diff); result.put("status","PROPOSED"); return result;
  }
}
