package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;

/** Cross-runner compatibility: compare task/settings/profile/catalog lineage, not run lineage. */
public final class RoutineShadowCompatibilityValidator {
  public void requireCompatible(CurrentSliceIdentity model, CurrentSliceIdentity session) {
    if (model == null
        || session == null
        || model.task() == null
        || session.task() == null
        || model.client() == null
        || session.client() == null) fail("IDENTITY_MISMATCH");
    model.validate();
    session.validate();
    if (!java.util.Objects.equals(model.schema(), session.schema())
        || model.controlAllowed()
        || session.controlAllowed()) fail("SHADOW_CONTROL_OR_SCHEMA_INVALID");
    if (!java.util.Objects.equals(model.task().taskKey(), session.task().taskKey())
        || !java.util.Objects.equals(model.task().settingsHash(), session.task().settingsHash())
        || !java.util.Objects.equals(model.task().profileId(), session.task().profileId())
        || !java.util.Objects.equals(model.task().profileHash(), session.task().profileHash())
        || !java.util.Objects.equals(model.task().catalogHash(), session.task().catalogHash()))
      fail("SHADOW_IDENTITY_INCOMPATIBLE");
    if (!"RUNELITE_CAPTURE".equals(session.client().clientKind())
        || !"runelite-capture/v1".equals(session.client().adapterContract())
        || !"RUNELITE".equals(session.client().domain())
        || session.controlAllowed()) fail("SHADOW_CLIENT_CAPABILITY_INVALID");
    if (model.model() == null
        || model.model().modelId() == null
        || model.model().modelHash() == null) fail("MODEL_IDENTITY_MISSING");
  }

  private static void fail(String code) {
    throw new RoutineCaptureIngestionService.RoutineCaptureException(
        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, code, code);
  }
}
