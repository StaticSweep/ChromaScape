# Analysis V1 Contract

## Overview

The Analysis V1 contract defines the request/response schema for the BMAD (Bot Metric Analysis & Diagnostics) system. It provides a structured way to send bot failure evidence to Claude API and receive actionable recommendations.

**Contract Version:** 1.0.0
**Status:** Active
**Created:** 2026-02-23

## Purpose

Enable automated bot failure analysis by:
1. Collecting bounded evidence windows (logs, tick logs, decisions)
2. Sending evidence to Claude API with a structured prompt
3. Receiving structured diagnosis with confidence levels and fix recommendations
4. Tracking analysis lifecycle and results in the database

## Schema

### Request Schema

```json
{
  "prompt": "string (required) - The question to ask about the evidence",
  "context": "string (optional) - Additional context about the failure",
  "evidence": {
    "scriptName": "string",
    "timeWindow": {
      "start": "ISO-8601 timestamp",
      "end": "ISO-8601 timestamp"
    },
    "tickWindow": {
      "startTick": "long (optional)",
      "endTick": "long (optional)"
    },
    "logs": [
      {
        "timestamp": "ISO-8601 timestamp",
        "level": "string (INFO/WARN/ERROR)",
        "message": "string",
        "source": "string (logger name)"
      }
    ],
    "tickLogs": [
      {
        "tick": "long",
        "timestamp": "ISO-8601 timestamp",
        "subsystem": "string",
        "severity": "string",
        "message": "string",
        "details": "string (optional)"
      }
    ],
    "decisions": [
      {
        "timestamp": "ISO-8601 timestamp",
        "decisionType": "string",
        "success": "boolean",
        "failureCode": "string (optional)",
        "targetEntity": "string (optional)",
        "context": "string (optional)"
      }
    ]
  },
  "config": {
    "maxRecommendations": "int (default: 5)",
    "includeEvidenceTrail": "boolean (default: true)",
    "minConfidence": "enum (HIGH/MEDIUM/LOW, default: LOW)"
  }
}
```

### Response Schema

```json
{
  "analysisId": "string (UUID)",
  "timestamp": "ISO-8601 timestamp",
  "status": "enum (SUCCESS/PARTIAL_SUCCESS/FAILURE)",
  "diagnosis": {
    "summary": "string (1-2 sentences)",
    "rootCause": "string (detailed explanation)",
    "confidence": "enum (HIGH/MEDIUM/LOW)",
    "recommendations": [
      {
        "priority": "int (1 = highest)",
        "title": "string",
        "description": "string",
        "confidence": "enum (HIGH/MEDIUM/LOW)",
        "affectedFiles": ["string"],
        "expectedImpact": "string"
      }
    ],
    "evidenceTrail": [
      {
        "type": "enum (GENERAL_LOGS/TICK_LOGS/DECISIONS)",
        "description": "string",
        "timestamp": "string",
        "excerpt": "string"
      }
    ]
  },
  "error": {
    "code": "string (error code)",
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

## Error Codes

| Code | Description | HTTP Equivalent |
|------|-------------|-----------------|
| `INVALID_REQUEST` | Request validation failed | 400 |
| `MISSING_REQUIRED_FIELD` | Required field is null or empty | 400 |
| `INVALID_TIME_WINDOW` | Time window start >= end | 400 |
| `INSUFFICIENT_EVIDENCE` | No evidence items found | 400 |
| `API_ERROR` | Claude API returned an error | 502 |
| `API_TIMEOUT` | Claude API call timed out | 504 |
| `API_RATE_LIMIT` | Rate limit exceeded | 429 |
| `API_AUTH_FAILED` | API key invalid or missing | 401 |
| `PARSE_ERROR` | Failed to parse API response | 500 |
| `EVIDENCE_COLLECTION_FAILED` | Evidence collection error | 500 |
| `INTERNAL_ERROR` | Unexpected internal error | 500 |

## Validation Rules

### Request Validation

1. **Prompt** - MUST be non-null and non-empty
2. **Evidence** - MUST be non-null
3. **Time Window** - MUST be non-null with start < end
4. **Tick Window** (if present) - MUST have startTick <= endTick
5. **Evidence Items** - MUST contain at least one log, tick log, or decision

### Response Validation

1. **Status** - MUST be non-null
2. **Diagnosis** - MUST be present if status is SUCCESS or PARTIAL_SUCCESS
   - Summary MUST be non-null and non-empty
   - Confidence MUST be non-null
3. **Error** - MUST be present if status is FAILURE
   - Code MUST be non-null and non-empty
   - Message MUST be non-null and non-empty

## Examples

### Example Request

```json
{
  "prompt": "Why did this fishing script fail to click on fishing spots?",
  "context": "Bot ran for 2 minutes then stopped interacting with game objects",
  "evidence": {
    "scriptName": "DemoFishingScript",
    "timeWindow": {
      "start": "2026-02-23T10:30:00",
      "end": "2026-02-23T10:32:00"
    },
    "tickWindow": {
      "startTick": 100,
      "endTick": 200
    },
    "logs": [
      {
        "timestamp": "2026-02-23T10:31:45",
        "level": "ERROR",
        "message": "Failed to find fishing spot colour",
        "source": "com.chromascape.scripts.DemoFishingScript"
      }
    ],
    "tickLogs": [
      {
        "tick": 150,
        "timestamp": "2026-02-23T10:31:45",
        "subsystem": "colour_detection",
        "severity": "ERROR",
        "message": "No matches found for colour FISHING_SPOT_BLUE",
        "details": "Searched viewport, 0 contours detected"
      }
    ],
    "decisions": [
      {
        "timestamp": "2026-02-23T10:31:45",
        "decisionType": "CLICK_FISHING_SPOT",
        "success": false,
        "failureCode": "NO_TARGET_FOUND",
        "targetEntity": "FISHING_SPOT",
        "context": "Attempted to find blue fishing spot"
      }
    ]
  },
  "config": {
    "maxRecommendations": 3,
    "includeEvidenceTrail": true,
    "minConfidence": "MEDIUM"
  }
}
```

### Example Success Response

```json
{
  "analysisId": "abc123-def456-ghi789",
  "timestamp": "2026-02-23T10:35:00",
  "status": "SUCCESS",
  "diagnosis": {
    "summary": "Colour detection failure due to incorrect colour definition or changed game graphics.",
    "rootCause": "The fishing spot colour 'FISHING_SPOT_BLUE' is not being detected in the viewport. This could indicate: (1) the colour definition in ColourInstances is incorrect, (2) game graphics changed, or (3) viewport is not focused on the correct area.",
    "confidence": "HIGH",
    "recommendations": [
      {
        "priority": 1,
        "title": "Verify colour definition accuracy",
        "description": "Use the colour picker tool to re-capture the FISHING_SPOT_BLUE colour from a live game screenshot. Compare RGB values with the current definition in ColourInstances.java.",
        "confidence": "HIGH",
        "affectedFiles": [
          "src/main/java/com/chromascape/utils/core/screen/colour/ColourInstances.java"
        ],
        "expectedImpact": "Should resolve detection if colour definition is outdated"
      },
      {
        "priority": 2,
        "title": "Increase colour tolerance",
        "description": "If colour definition is correct, try increasing the colour tolerance (fuzzy matching) in the detection algorithm to account for lighting variations.",
        "confidence": "MEDIUM",
        "affectedFiles": [
          "src/main/java/com/chromascape/utils/core/screen/topology/ColourContours.java"
        ],
        "expectedImpact": "May reduce false negatives but could introduce false positives"
      }
    ],
    "evidenceTrail": [
      {
        "type": "TICK_LOGS",
        "description": "Colour detection failure at tick 150",
        "timestamp": "2026-02-23T10:31:45 (tick 150)",
        "excerpt": "No matches found for colour FISHING_SPOT_BLUE"
      },
      {
        "type": "DECISIONS",
        "description": "Failed decision to click fishing spot",
        "timestamp": "2026-02-23T10:31:45",
        "excerpt": "CLICK_FISHING_SPOT failed with NO_TARGET_FOUND"
      }
    ]
  },
  "metadata": {
    "model": "claude-opus-4-6",
    "analysisTimeMs": 3500,
    "evidenceCount": 3,
    "tokensUsed": 1250
  }
}
```

### Example Failure Response

```json
{
  "analysisId": "xyz789-abc123",
  "timestamp": "2026-02-23T10:35:00",
  "status": "FAILURE",
  "error": {
    "code": "API_TIMEOUT",
    "message": "Claude API request timed out after 120 seconds",
    "details": "java.net.http.HttpTimeoutException: request timed out",
    "timestamp": "2026-02-23T10:35:00"
  },
  "metadata": {
    "model": "claude-opus-4-6",
    "analysisTimeMs": 120000,
    "evidenceCount": 3
  }
}
```

## Version History

### Version 1.0.0 (2026-02-23)
- Initial contract definition
- Support for 3 evidence types: logs, tick logs, decisions
- Structured diagnosis with confidence levels
- Fix recommendations with priority ranking
- Evidence trail for transparency

## Future Enhancements

Planned for future versions:
- V1.1: Add support for snapshot evidence (screenshots)
- V1.2: Add timeline marker evidence
- V1.3: Add video frame references
- V1.4: Add code diff evidence (for retesting)
- V2.0: Support for batch analysis across multiple iterations
