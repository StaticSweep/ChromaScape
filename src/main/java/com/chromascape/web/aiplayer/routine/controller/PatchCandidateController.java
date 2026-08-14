package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.routine.service.PatchCandidateWorkflowService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai-routine/patches")
public class PatchCandidateController {
  private final PatchCandidateWorkflowService service;
  public PatchCandidateController(PatchCandidateWorkflowService service){this.service=service;}
  @PostMapping("/from-failure") public ResponseEntity<Object> select(@RequestBody Map<String,Object> request){return ResponseEntity.ok(service.fromFailure(request));}
  @PostMapping("/{proposalId}/approve") public ResponseEntity<Object> approve(@PathVariable String proposalId,@RequestBody Map<String,String> request){return ResponseEntity.ok(service.approve(proposalId,request==null?null:request.get("approver_id")));}
  @PostMapping("/{proposalId}/build") public ResponseEntity<Object> build(@PathVariable String proposalId){return ResponseEntity.accepted().body(service.build(proposalId));}
  @GetMapping("/{proposalId}") public ResponseEntity<Object> get(@PathVariable String proposalId){return ResponseEntity.ok(service.get(proposalId));}
  @ExceptionHandler(PatchCandidateWorkflowService.PatchWorkflowException.class) public ResponseEntity<Object> error(PatchCandidateWorkflowService.PatchWorkflowException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
}
