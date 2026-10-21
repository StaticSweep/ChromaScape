package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import java.security.Principal;
import org.junit.jupiter.api.Test;

class MouseVideoAccessTest {

  private final MouseVideoAccess access = new MouseVideoAccess();

  @Test
  void missingPrincipalIsRefused() {
    assertThatThrownBy(() -> access.require(null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_UNAUTHENTICATED);
  }

  @Test
  void blankPrincipalIsRefused() {
    Principal blank = () -> "  ";
    assertThatThrownBy(() -> access.require(blank))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_UNAUTHENTICATED);
  }

  @Test
  void principalIsBothOwnerAndAuditActor() {
    Principal principal = () -> "trusted-user";
    MouseVideoAccess.Actor actor = access.require(principal);
    assertThat(actor.owner()).isEqualTo("trusted-user");
    assertThat(actor.auditActor()).isEqualTo("trusted-user");
  }
}
