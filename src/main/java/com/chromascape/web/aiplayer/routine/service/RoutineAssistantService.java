package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RoutineAssistantService {
  private final RoutineAssistantProvider provider; private final Map<String, Conversation> conversations=new ConcurrentHashMap<>();
  public RoutineAssistantService(RoutineAssistantProvider provider){this.provider=provider;}
  public Map<String,Object> create(String runId){String id="conv_"+UUID.randomUUID().toString().replace("-","");Conversation c=new Conversation(id,runId); conversations.put(id,c); c.event("conversation.started",Map.of("run_id",runId)); return c.response();}
  public Map<String,Object> message(String id,RoutineApiDtos.AssistantMessageRequest request){Conversation c=get(id); c.event("message.accepted",Map.of("message",request.message())); Map<String,Object> proposal=provider.propose(id,request.message(),Map.of("run_id",c.runId)); c.proposal=proposal; c.event("proposal.created",proposal); c.event("stream.complete",Map.of()); return proposal;}
  public Map<String,Object> stop(String id){Conversation c=get(id); c.event("generation.stopped",Map.of()); return c.response();}
  public List<Map<String,Object>> events(String id,long after){Conversation c=get(id); return c.events.stream().filter(event->((Number)event.get("id")).longValue()>after).toList();}
  public Map<String,Object> accept(String proposalId){for(Conversation c:conversations.values())if(c.proposal!=null&&proposalId.equals(c.proposal.get("proposal_id"))){c.event("proposal.accepted",Map.of("draft_created",true));return Map.of("proposal_id",proposalId,"status","DRAFT_CREATED","published",false,"approved_patch",false);}throw new RoutineCaptureServiceException(HttpStatus.NOT_FOUND,"NOT_FOUND","proposal not found");}
  public Map<String,Object> reject(String proposalId,String reason){for(Conversation c:conversations.values())if(c.proposal!=null&&proposalId.equals(c.proposal.get("proposal_id"))){c.event("proposal.rejected",Map.of("reason",reason==null?"":reason));return Map.of("proposal_id",proposalId,"status","REJECTED");}throw new RoutineCaptureServiceException(HttpStatus.NOT_FOUND,"NOT_FOUND","proposal not found");}
  private Conversation get(String id){Conversation c=conversations.get(id);if(c==null)throw new RoutineCaptureServiceException(HttpStatus.NOT_FOUND,"NOT_FOUND","conversation not found");return c;}
  public static final class RoutineCaptureServiceException extends RuntimeException{final HttpStatus status;final String code;RoutineCaptureServiceException(HttpStatus s,String c,String m){super(m);status=s;code=c;}public HttpStatus status(){return status;}public String code(){return code;}}
  private static final class Conversation{final String id,runId;final List<Map<String,Object>> events=new ArrayList<>();Map<String,Object> proposal;long next=1;Conversation(String id,String runId){this.id=id;this.runId=runId;}void event(String type,Map<String,Object> data){Map<String,Object> e=new LinkedHashMap<>();e.put("id",next++);e.put("type",type);e.put("created_at",Instant.now().toString());e.put("data",data);events.add(e);}Map<String,Object> response(){return Map.of("conversation_id",id,"run_id",runId,"next_event_id",next);}}
}
