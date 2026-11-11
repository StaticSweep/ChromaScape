package com.chromascape.web.gameplay.provider.runtime;

/**
 * SC-1429/v2 proposal schema.
 *
 * <p>The v1 schema asks only for a {@code semanticAction}; the v2 contract additionally requires the
 * provider's GoalPlan, the prompt identity, the confidence triple and the eligibility digest it was
 * shown. {@code GameplayV2ProviderAdapter} validates exactly that shape, so a turn that is meant to
 * produce a v2 decision must be asked for it — a v1 output can never be relabelled.
 *
 * <p>This schema is the request side only. The authoritative validation remains the frozen v2
 * contract schema in {@code contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json}; this
 * narrower shape keeps the provider from emitting fields the runtime would have to discard.
 */
public final class ProviderProposalSchemaV2 {
  /** The authoritative proposal schema embedded in every sc-1429/v2 prompt. */
  public static final String JSON =
      """
      {"type":"object","additionalProperties":false,
       "required":["contractVersion","kind","provider","modelVersion","decisionId","promptId",
         "policyConfidence","adapterObservationConfidence","knowledgeFreshnessConfidence",
         "eligibleInputDigest","goalPlan","evidenceRefs"],
       "properties":{
         "contractVersion":{"type":"string","const":"sc-1429/v2"},
         "kind":{"type":"string","const":"SELECTED"},
         "provider":{"type":"string","enum":["CLAUDE","CODEX","MISTRAL"]},
         "modelVersion":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
         "decisionId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
         "promptId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
         "policyConfidence":{"type":"number","minimum":0,"maximum":1},
         "adapterObservationConfidence":{"type":"number","minimum":0,"maximum":1},
         "knowledgeFreshnessConfidence":{"type":"number","minimum":0,"maximum":1},
         "eligibleInputDigest":{"type":"string","pattern":"^sha256:[0-9a-f]{64}$"},
         "goalPlan":{"type":"object","additionalProperties":false,
           "required":["contractVersion","goalPlanId","goalPlanDigest","mode","identities",
             "sourceDecisionId","deadline","terminalObjective","aggregateBudgets","nodes"],
           "properties":{
             "contractVersion":{"type":"string","const":"sc-1429/v2"},
             "goalPlanId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
             "goalPlanDigest":{"type":"string","pattern":"^sha256:[0-9a-f]{64}$"},
             "mode":{"type":"string","const":"LIVE_TASK"},
             "identities":{"type":"object","additionalProperties":false,
               "required":["accountId","clientId","environmentId","runId","taskId","sessionId",
                 "recordingId","sourceId"],
               "properties":{"accountId":{"type":"string"},"clientId":{"type":"string"},
                 "environmentId":{"type":"string"},"runId":{"type":"string"},
                 "taskId":{"type":"string"},"sessionId":{"type":"string"},
                 "recordingId":{"type":"string"},"sourceId":{"type":"string"}}},
             "sourceDecisionId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
             "deadline":{"type":"string","format":"date-time"},
             "terminalObjective":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
             "aggregateBudgets":{"type":"object","additionalProperties":false,
               "required":["maxActions","maxAttempts","maxElapsedMs"],
               "properties":{"maxActions":{"type":"integer","minimum":1,"maximum":20},
                 "maxAttempts":{"type":"integer","minimum":1,"maximum":3},
                 "maxElapsedMs":{"type":"integer","minimum":1000,"maximum":1800000}}},
             "nodes":{"type":"array","minItems":1,"maxItems":16,"items":{
               "type":"object","additionalProperties":false,
               "required":["nodeId","ordinal","taskKey","taskSkillRefs","settings",
                 "prerequisiteNodeIds","preconditions","expectedObservationRevision",
                 "expectedTaskRevision","expectedTransition","verifierId","budgets",
                 "fallbackNodeId","failureCode","evidenceRefs"],
               "properties":{
                 "nodeId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
                 "ordinal":{"type":"integer","minimum":0,"maximum":15},
                 "taskKey":{"type":"string","const":"FISHING_TRAINING"},
                 "taskSkillRefs":{"type":"array","minItems":1,"maxItems":8,"items":{
                   "type":"object","additionalProperties":false,
                   "required":["taskSkillId","version","artifactDigest","maturity",
                     "capabilityRequirements","provenance"],
                   "properties":{
                     "taskSkillId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
                     "version":{"type":"integer","minimum":1,"maximum":65535},
                     "artifactDigest":{"type":"string","pattern":"^sha256:[0-9a-f]{64}$"},
                     "maturity":{"type":"string","enum":["CANDIDATE","VALIDATED","TRUSTED"]},
                     "capabilityRequirements":{"type":"array","minItems":1,"maxItems":16,
                       "items":{"type":"string"}},
                     "provenance":{"type":"array","minItems":1,"maxItems":8,
                       "items":{"type":"string"}}}}},
                 "settings":{"type":"object","additionalProperties":false,
                   "required":["targetLevel","areaPolicy"],
                   "properties":{"targetLevel":{"type":"integer","minimum":2,"maximum":99},
                     "areaPolicy":{"type":"string",
                       "enum":["LUMBRIDGE","DRAYNOR","BARBARIAN_VILLAGE"]}}},
                 "prerequisiteNodeIds":{"type":"array","maxItems":15,"items":{"type":"string"}},
                 "preconditions":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string"}},
                 "expectedObservationRevision":{"type":"integer","minimum":0},
                 "expectedTaskRevision":{"type":"integer","minimum":0},
                 "expectedTransition":{"type":"string",
                   "enum":["FISHING_XP_INCREASED","TARGET_LEVEL_REACHED"]},
                 "verifierId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
                 "budgets":{"type":"object","additionalProperties":false,
                   "required":["maxActions","maxAttempts","maxElapsedMs"],
                   "properties":{"maxActions":{"type":"integer","minimum":1,"maximum":20},
                     "maxAttempts":{"type":"integer","minimum":1,"maximum":3},
                     "maxElapsedMs":{"type":"integer","minimum":1000,"maximum":1800000}}},
                 "fallbackNodeId":{"type":["string","null"]},
                 "failureCode":{"type":["string","null"]},
                 "evidenceRefs":{"type":"array","maxItems":32,"items":{"type":"string"}}}}}
           }},
         "evidenceRefs":{"type":"array","maxItems":32,"items":{"type":"string"}}
       }}
      """;

  private ProviderProposalSchemaV2() {}
}
