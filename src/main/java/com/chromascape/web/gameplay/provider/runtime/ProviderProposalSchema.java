package com.chromascape.web.gameplay.provider.runtime;

final class ProviderProposalSchema {
  static final String JSON =
      """
      {"type":"object","additionalProperties":false,
       "required":["contractVersion","decisionId","policyConfidence","semanticAction","evidenceRefs"],
       "properties":{
         "contractVersion":{"type":"string","const":"sc-1429/v1"},
         "decisionId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"},
         "policyConfidence":{"type":"number","minimum":0,"maximum":1},
         "semanticAction":{"anyOf":[
           {"type":"object","additionalProperties":false,
            "required":["kind","taskKey","settings","target","expectedTransition","preconditions","verifierId","decisionId","evidenceRefs"],
            "properties":{"kind":{"type":"string","const":"START_FISHING_TASK"},"taskKey":{"type":"string","const":"FISHING_TRAINING"},
              "settings":{"type":"object","additionalProperties":false,"required":["targetLevel","areaPolicy"],
                "properties":{"targetLevel":{"type":"integer","minimum":2,"maximum":99},"areaPolicy":{"type":"string","enum":["LUMBRIDGE","DRAYNOR","BARBARIAN_VILLAGE"]}}},
              "target":{"type":"string","const":"FISHING"},"expectedTransition":{"type":"string","enum":["FISHING_XP_INCREASED","TARGET_LEVEL_REACHED"]},
              "preconditions":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"string"}},
              "verifierId":{"type":"string"},"decisionId":{"type":"string"},
              "evidenceRefs":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"string"}}}},
           {"type":"object","additionalProperties":false,"required":["kind","decisionId","reason"],
            "properties":{"kind":{"type":"string","const":"ABSTAIN"},"decisionId":{"type":"string"},
              "reason":{"type":"string","enum":["NO_ELIGIBLE_TASK","STALE_KNOWLEDGE","UNSUPPORTED_CAPABILITY","LOW_CONFIDENCE","POLICY_DENIED"]}}}
         ]},
         "evidenceRefs":{"type":"array","maxItems":32,"items":{"type":"string"}}
       }}
      """;

  private ProviderProposalSchema() {}
}
