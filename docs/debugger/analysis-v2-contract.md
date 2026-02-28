# Analysis V2 Contract

## Overview

The Analysis V2 contract defines the request/response schema for the BMAD (Bot Metric Analysis & Diagnostics) system with autonomous fix loop support.

**Breaking Change from V1:** V2 adds explicit contract fields for structured patch drafts and retest plans required for the autonomous fix loop vision.

**Contract Version:** 2.0.0
**Status:** Active
**Created:** 2026-02-28
**Supersedes:** analysis-v1-contract.md (V1 now deprecated)

## Purpose

Enable automated bot failure analysis with autonomous patch generation by:
1. Collecting bounded evidence windows via structured selectors
2. Sending evidence to Claude API with V2 prompt structure
3. Receiving structured diagnosis with **explicit patchDraft** containing code changes
4. Receiving **retestPlan** with test steps and success criteria
5. Tracking analysis lifecycle with V2 schema support

## Breaking Changes from V1

### Request Changes
- **Added** `promptType` (enum: ISSUE | FEATURE) - required
- **Added** `target` object with `scriptName`, `runId`, `sessionBounds` - required
- **Replaced** `evidence` bundle with `evidenceSelectors` array - required
- **Replaced** flat evidence structure with selector-based filtering

### Response Changes
- **Added** `impactedModules` (string array) - required for SUCCESS
- **Added** `patchDraft` object with structured changes - required for SUCCESS
- **Added** `retestPlan` object with test steps - required for SUCCESS
- **Added** `evidenceReferences` (string array) - required for SUCCESS
- **Changed** `diagnosis` from nested class to structured `AnalysisV2Diagnosis` object
- **Preserved** V1 diagnosis semantics: `summary`, `rootCause`, `confidence`, `recommendations`

### Validation Changes
- V1 requests will fail with `INVALID_SCHEMA_VERSION` error
- SUCCESS responses without `patchDraft`, `retestPlan`, `impactedModules`, or `evidenceReferences` will fail validation
- Parse-hardening pipeline retries with repair prompts on validation failure

## Schema

### Request Schema

```json
{
  "schemaVersion": "2.0.0",
  "promptType": "ISSUE | FEATURE",
  "target": {
    "scriptName": "string (required)",
    "runId": "string (optional)",
    "sessionBounds": {
      "start": "ISO-8601 timestamp",
      "end": "ISO-8601 timestamp"
    }
  },
  "prompt": "string (required)",
  "context": "string (optional)",
  "evidenceSelectors": [
    {
      "type": "GENERAL_LOGS | TICK_LOGS | DECISIONS",
      "required": "boolean (default: false)",
      "maxCount": "int (optional)",
      "timeWindow": {
        "start": "ISO-8601 timestamp",
        "end": "ISO-8601 timestamp"
      },
      "tickWindow": {
        "startTick": "long (optional)",
        "endTick": "long (optional)"
      },
      "filters": [
        {
          "key": "string",
          "value": "string",
          "operator": "EQUALS | CONTAINS | REGEX | GREATER_THAN | LESS_THAN"
        }
      ]
    }
  ],
  "config": {
    "maxRecommendations": "int (default: 5)",
    "includeEvidenceTrail": "boolean (default: true)",
    "minConfidence": "HIGH | MEDIUM | LOW (default: LOW)"
  }
}
```

### Response Schema

```json
{
  "schemaVersion": "2.0.0",
  "analysisId": "string (UUID)",
  "timestamp": "ISO-8601 timestamp",
  "status": "SUCCESS | PARTIAL_SUCCESS | FAILURE",

  "diagnosis": {
    "summary": "string (1-2 sentences)",
    "rootCause": "string (detailed explanation)",
    "confidence": "HIGH | MEDIUM | LOW",
    "recommendations": [
      {
        "priority": "int (1 = highest)",
        "title": "string",
        "description": "string",
        "confidence": "HIGH | MEDIUM | LOW",
        "affectedFiles": ["string"],
        "expectedImpact": "string"
      }
    ]
  },

  "impactedModules": ["module1", "module2"],

  "patchDraft": {
    "patchId": "string (unique identifier)",
    "description": "string (human-readable)",
    "affectedFiles": ["file/path1.java", "file/path2.java"],
    "changes": [
      {
        "filePath": "string",
        "oldText": "string (exact code to replace)",
        "newText": "string (exact new code)",
        "lineNumber": "int (optional)",
        "changeType": "MODIFY | INSERT | DELETE | REPLACE"
      }
    ],
    "confidence": "HIGH | MEDIUM | LOW",
    "rationale": "string (why this patch will fix the issue)"
  },

  "retestPlan": {
    "testSteps": [
      {
        "stepNumber": "int",
        "description": "string",
        "expectedResult": "string",
        "actionType": "RUN_SCRIPT | VERIFY_BEHAVIOR | CHECK_LOGS | INSPECT_STATE | MANUAL_VERIFY",
        "target": "string (optional)"
      }
    ],
    "successCriteria": ["criteria1", "criteria2"],
    "rollbackPlan": "string",
    "estimatedDuration": "string (e.g., '5 minutes')"
  },

  "evidenceReferences": ["ref1", "ref2"],

  "error": {
    "code": "string",
    "message": "string",
    "details": "string (optional)",
    "timestamp": "ISO-8601 timestamp"
  },

  "metadata": {
    "model": "string (e.g., claude-opus-4-6)",
    "analysisTimeMs": "long",
    "evidenceCount": "int",
    "tokensUsed": "int (optional)",
    "extra": {}
  }
}
```

## Required Fields by Status

### SUCCESS Status
All of the following are **required**:
- `diagnosis` (with `summary`, `rootCause`, `confidence`)
- `impactedModules` (non-empty array)
- `patchDraft` (with `patchId`, `description`, `affectedFiles`, `changes`)
- `retestPlan` (with `testSteps`, `successCriteria`)
- `evidenceReferences` (non-empty array)

### PARTIAL_SUCCESS Status
- `diagnosis` (with `summary`, `rootCause`, `confidence`) - **required**
- `impactedModules`, `patchDraft`, `retestPlan`, `evidenceReferences` - optional

### FAILURE Status
- `error` (with `code`, `message`) - **required**

## Error Codes

| Code | Description | HTTP Equivalent |
|------|-------------|-----------------|
| `INVALID_SCHEMA_VERSION` | Schema version is not "2.x.x" | 400 |
| `MISSING_REQUIRED_FIELD` | Required field is null or empty | 400 |
| `INVALID_TIME_WINDOW` | Time window start >= end | 400 |
| `INSUFFICIENT_EVIDENCE` | No evidence items found | 400 |
| `MISSING_SUCCESS_FIELDS` | SUCCESS missing patchDraft/retestPlan/etc | 400 |
| `MISSING_ERROR_ON_FAILURE` | FAILURE status without error field | 400 |
| `INVALID_RESPONSE_SCHEMA` | Response structure validation failed | 400 |
| `API_ERROR` | Claude API returned an error | 502 |
| `API_TIMEOUT` | Claude API call timed out | 504 |
| `API_RATE_LIMIT` | Rate limit exceeded | 429 |
| `API_AUTH_FAILED` | API key invalid or missing | 401 |
| `PARSE_ERROR` | Failed to parse API response | 500 |
| `EVIDENCE_COLLECTION_FAILED` | Evidence collection error | 500 |
| `INTERNAL_ERROR` | Unexpected internal error | 500 |
| `INVALID_REQUEST` | Request validation failed | 400 |

## Parse-Hardening Pipeline

The V2 implementation includes a parse-hardening pipeline to handle LLM output variability:

1. **Initial Parse:** Attempt to parse LLM response as V2 JSON
2. **Validation:** Validate response against V2 schema requirements
3. **Retry on Failure:** If validation fails, retry up to 2 times with repair prompt
4. **Repair Prompt:** Adds explicit instruction about missing/invalid fields
5. **Deterministic FAILURE:** After retry exhaustion, return structured FAILURE with error code

This ensures graceful degradation when LLM output doesn't match strict schema requirements.

## Examples

### Example Request (V2)

```json
{
  "schemaVersion": "2.0.0",
  "promptType": "ISSUE",
  "target": {
    "scriptName": "DemoFishingScript",
    "runId": "run-20260228-12345",
    "sessionBounds": {
      "start": "2026-02-28T10:30:00",
      "end": "2026-02-28T10:32:00"
    }
  },
  "prompt": "Why did this fishing script fail to click on fishing spots?",
  "context": "Bot ran for 2 minutes then stopped interacting with game objects",
  "evidenceSelectors": [
    {
      "type": "GENERAL_LOGS",
      "required": true,
      "maxCount": 50,
      "timeWindow": {
        "start": "2026-02-28T10:30:00",
        "end": "2026-02-28T10:32:00"
      }
    },
    {
      "type": "TICK_LOGS",
      "required": true,
      "tickWindow": {
        "startTick": 100,
        "endTick": 200
      }
    },
    {
      "type": "DECISIONS",
      "required": true,
      "filters": [
        {
          "key": "success",
          "value": "false",
          "operator": "EQUALS"
        }
      ]
    }
  ]
}
```

### Example Success Response (V2)

```json
{
  "schemaVersion": "2.0.0",
  "analysisId": "abc123-v2-def456",
  "timestamp": "2026-02-28T10:35:00",
  "status": "SUCCESS",
  "diagnosis": {
    "summary": "Colour detection failure due to incorrect colour definition.",
    "rootCause": "The fishing spot colour 'FISHING_SPOT_BLUE' is not being detected. The colour definition in ColourInstances is incorrect or game graphics changed.",
    "confidence": "HIGH",
    "recommendations": [
      {
        "priority": 1,
        "title": "Verify colour definition accuracy",
        "description": "Re-capture the FISHING_SPOT_BLUE colour from live screenshot.",
        "confidence": "HIGH",
        "affectedFiles": ["src/main/java/com/chromascape/utils/core/screen/colour/ColourInstances.java"],
        "expectedImpact": "Should resolve detection if colour definition is outdated"
      }
    ]
  },
  "impactedModules": ["colour-detection", "fishing-logic"],
  "patchDraft": {
    "patchId": "patch-colour-fix-v1",
    "description": "Update FISHING_SPOT_BLUE colour definition",
    "affectedFiles": ["src/main/java/com/chromascape/utils/core/screen/colour/ColourInstances.java"],
    "changes": [
      {
        "filePath": "src/main/java/com/chromascape/utils/core/screen/colour/ColourInstances.java",
        "oldText": "public static final ColourRGB FISHING_SPOT_BLUE = new ColourRGB(65, 105, 225);",
        "newText": "public static final ColourRGB FISHING_SPOT_BLUE = new ColourRGB(70, 110, 230);",
        "lineNumber": 42,
        "changeType": "MODIFY"
      }
    ],
    "confidence": "HIGH",
    "rationale": "Game graphics update shifted the blue pixel values slightly. Updated RGB values to match current fishing spot appearance."
  },
  "retestPlan": {
    "testSteps": [
      {
        "stepNumber": 1,
        "description": "Run the fishing script with updated colour definition",
        "expectedResult": "Bot successfully detects and clicks fishing spots",
        "actionType": "RUN_SCRIPT",
        "target": "DemoFishingScript"
      },
      {
        "stepNumber": 2,
        "description": "Verify colour detection logs show successful matches",
        "expectedResult": "Logs show 'Found FISHING_SPOT_BLUE at coordinates' messages",
        "actionType": "CHECK_LOGS"
      }
    ],
    "successCriteria": [
      "Fishing spots detected within 10 seconds",
      "At least 5 successful clicks per minute",
      "No 'No matches found for colour FISHING_SPOT_BLUE' errors"
    ],
    "rollbackPlan": "Revert to original RGB values if false positives increase",
    "estimatedDuration": "5 minutes"
  },
  "evidenceReferences": [
    "Tick 150: No matches found for colour FISHING_SPOT_BLUE",
    "Decision 142: CLICK_FISHING_SPOT failed with NO_TARGET_FOUND",
    "Log 10:31:45: Failed to find fishing spot colour"
  ],
  "metadata": {
    "model": "claude-opus-4-6",
    "analysisTimeMs": 4200,
    "evidenceCount": 3,
    "tokensUsed": 1850
  }
}
```

### Example Failure Response (V2)

```json
{
  "schemaVersion": "2.0.0",
  "analysisId": "xyz789-v2-abc123",
  "timestamp": "2026-02-28T10:35:00",
  "status": "FAILURE",
  "error": {
    "code": "API_TIMEOUT",
    "message": "Claude API request timed out after 120 seconds",
    "details": "java.net.http.HttpTimeoutException: request timed out",
    "timestamp": "2026-02-28T10:35:00"
  },
  "metadata": {
    "model": "claude-opus-4-6",
    "analysisTimeMs": 120000,
    "evidenceCount": 3
  }
}
```

## Migration from V1

### For Runtime Consumers

1. **Update imports:** Replace `AnalysisV1Request` → `AnalysisV2Request`, `AnalysisV1Response` → `AnalysisV2Response`
2. **Update request building:** Use V2 target structure and evidence selectors
3. **Update response handling:** Access `diagnosis` as `AnalysisV2Diagnosis` object (not nested class)
4. **Use patchDraft:** Access `response.getPatchDraft()` for structured code changes

### For Data Access

- No database migration required (JSONB columns store full request/response)
- Read compatibility layer handles mixed V1/V2 records
- Use `PatchLoopPersistenceService.readAnalysisResponse()` for V1/V2-agnostic access

### Breaking Change Checklist

- [x] Update request building to use V2 target and selectors
- [x] Update response handling to use V2 diagnosis object
- [x] Update GENERATE_PATCH to use patchDraft.changes
- [x] Update persistence to handle V2 writes
- [ ] Update UI/API to display V2 fields (patchDraft, retestPlan)
- [ ] Update tests for V2 contract

## Version History

### Version 2.0.0 (2026-02-28)
- Breaking change from V1
- Added `promptType`, `target`, `evidenceSelectors` to request
- Added `patchDraft`, `retestPlan`, `impactedModules`, `evidenceReferences` to response
- Structured `diagnosis` as separate object (preserved V1 semantics)
- Parse-hardening pipeline with retry logic
- V1/V2 read compatibility in persistence layer

### Version 1.0.0 (2026-02-23)
- Initial contract definition (now deprecated, see analysis-v1-contract.md)
