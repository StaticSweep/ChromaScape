package com.chromascape.web.debug.buildergoal.recovery.config;

/** Request attribute carrying the server-resolved recovery operator identity. */
public final class RecoveryOperatorContext {

  public static final String OPERATOR_ID_ATTRIBUTE = "recovery.operator.id";
  public static final String OPERATOR_ROLE_ATTRIBUTE = "recovery.operator.role";

  public static final String ROLE_OPERATOR = "OPERATOR";

  private RecoveryOperatorContext() {}
}
