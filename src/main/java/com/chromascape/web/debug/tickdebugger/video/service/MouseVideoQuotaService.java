package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoIntakeReservation;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoIntakeReservationRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoOwnerBudget;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoOwnerBudgetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Per-attempt intake accounting; every mutation locks the owner before the reservation. */
@Service
public class MouseVideoQuotaService {
  private static final long RESERVATION_TTL_SECONDS = 3_600L;
  private final MouseVideoProperties properties;
  private final MouseVideoOwnerBudgetRepository budgets;
  private final MouseVideoIntakeReservationRepository reservations;
  private final EntityManager entityManager;

  public MouseVideoQuotaService(
      MouseVideoProperties properties,
      MouseVideoOwnerBudgetRepository budgets,
      MouseVideoIntakeReservationRepository reservations,
      EntityManager entityManager) {
    this.properties = properties;
    this.budgets = budgets;
    this.reservations = reservations;
    this.entityManager = entityManager;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MouseVideoIntakeReservation reserve(String owner, String requestId, long declaredBytes) {
    long bounded =
        declaredBytes <= 0
            ? properties.getMaxUploadBytes()
            : Math.min(declaredBytes, properties.getMaxUploadBytes());
    var budget = lockBudget(owner);
    Instant expiry = budgets.databaseNow().plusSeconds(RESERVATION_TTL_SECONDS);
    var existing = reservations.findByOwnerAndRequestId(owner, requestId);
    if (existing.isPresent()) {
      var reservation = existing.get();
      entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
      if (MouseVideoIntakeReservation.STATE_COMMITTED.equals(reservation.getState())) {
        return reservation;
      }
      long held = reservation.isHeld() ? reservation.getDeclaredBytes() : 0L;
      long amount = Math.max(held, bounded);
      requireCapacity(budget, amount - held);
      budget.reserve(amount - held);
      reservation.hold(amount, expiry);
      return reservations.save(reservation);
    }
    requireCapacity(budget, bounded);
    budget.reserve(bounded);
    return reservations.save(
        new MouseVideoIntakeReservation(owner, requestId, null, bounded, expiry));
  }

  @Transactional
  public void commit(UUID reservationId, String digest, String checksum, long actualBytes) {
    var owner =
        reservations
            .findOwner(reservationId)
            .orElseThrow(() -> invalid("Intake reservation is missing"));
    var budget = lockBudget(owner);
    var reservation = lockReservation(reservationId);
    if (MouseVideoIntakeReservation.STATE_COMMITTED.equals(reservation.getState())) return;
    if (!reservation.isHeld() || !reservation.getExpiresAt().isAfter(budgets.databaseNow())) {
      throw invalid("Intake reservation expired; retry the upload");
    }
    long other = budget.committedBytes() - reservation.getDeclaredBytes();
    long actual = Math.max(0L, actualBytes);
    if (actual > properties.getMaxSourceDiskBytes() - other) {
      throw quotaExceeded();
    }
    budget.charge(reservation.getDeclaredBytes(), actual);
    reservation.commit(digest, checksum, actual);
  }

  @Transactional
  public void release(UUID reservationId) {
    if (reservationId == null) return;
    reservations
        .findOwner(reservationId)
        .ifPresent(
            owner -> {
              var budget = lockBudget(owner);
              var reservation = lockReservation(reservationId);
              if (reservation.isHeld()) {
                budget.release(reservation.getDeclaredBytes());
                reservation.release();
              }
            });
  }

  @Transactional
  public int expireReservations() {
    Instant cutoff = budgets.databaseNow();
    var expired =
        reservations.findByStateAndExpiresAtBefore(MouseVideoIntakeReservation.STATE_HELD, cutoff);
    expired.sort(
        Comparator.comparing(MouseVideoIntakeReservation::getOwner)
            .thenComparing(MouseVideoIntakeReservation::getId));
    int count = 0;
    for (var candidate : expired) {
      var budget = lockBudget(candidate.getOwner());
      var reservation = lockReservation(candidate.getId());
      if (reservation.isHeld() && reservation.getExpiresAt().isBefore(cutoff)) {
        budget.release(reservation.getDeclaredBytes());
        reservation.release();
        count++;
      }
    }
    return count;
  }

  private MouseVideoOwnerBudget lockBudget(String owner) {
    entityManager.flush();
    budgets.ensureOwner(owner);
    var budget = budgets.findForUpdate(owner).orElseThrow(() -> quotaExceeded());
    entityManager.refresh(budget, LockModeType.PESSIMISTIC_WRITE);
    return budget;
  }

  private MouseVideoIntakeReservation lockReservation(UUID id) {
    var reservation =
        reservations.findForUpdate(id).orElseThrow(() -> invalid("Intake reservation is missing"));
    entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
    return reservation;
  }

  private void requireCapacity(MouseVideoOwnerBudget budget, long incoming) {
    if (incoming > properties.getMaxSourceDiskBytes() - budget.committedBytes())
      throw quotaExceeded();
  }

  private static MouseVideoException quotaExceeded() {
    return new MouseVideoException(
        MouseVideoException.CODE_QUOTA_EXCEEDED, "Source disk quota would be exceeded");
  }

  private static MouseVideoException invalid(String message) {
    return new MouseVideoException(MouseVideoException.CODE_INVALID_REQUEST, message);
  }
}
