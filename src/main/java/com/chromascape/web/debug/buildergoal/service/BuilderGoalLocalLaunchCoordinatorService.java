package com.chromascape.web.debug.buildergoal.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Serializes local Micro6 executor launches so that only one in-progress (re)launch exists per
 * launcher-machine + executor-port resource at a time.
 *
 * <p>Uses token-owned lease handles: each acquisition produces an immutable {@link LeaseHandle}
 * with a random owner token. Only the exact token holder can release the lease. A held resource
 * always conflicts, including same-account requests — same-account deduplication remains the
 * responsibility of {@link BuilderGoalLaunchOperationService}.
 *
 * <p>Conflicts return a sanitized {@link Conflict} describing the current workflow and account
 * rather than raw account keys. Leases are never forcibly expired while a worker may still mutate
 * Micro6.
 */
@Service
public class BuilderGoalLocalLaunchCoordinatorService {

  private static final Logger logger =
      LoggerFactory.getLogger(BuilderGoalLocalLaunchCoordinatorService.class);

  /** Immutable lease handle with a unique owner token used for ownership verification. */
  public record LeaseHandle(
      String machineUuid, int executorPort, String accountKey, String ownerId, UUID ownerToken) {}

  /**
   * Sanitized conflict describing the current lease holder. Exposes only the generic workflow type
   * and the owner ID (which identifies the owning workflow, not the account). The raw account key
   * is retained on the lease handle for internal debugging and is never rendered to operators or
   * logged into user-facing messages.
   */
  public record Conflict(String workflowType, String ownerId) {

    public static Conflict of(LeaseHandle handle) {
      return new Conflict(workflowTypeFor(handle.ownerId()), handle.ownerId());
    }

    private static String workflowTypeFor(String ownerId) {
      if (ownerId == null) {
        return "another workflow";
      }
      if (ownerId.startsWith("recovery:")) {
        return "recovery";
      }
      if (ownerId.startsWith("buildergoal_")) {
        return "run task launch";
      }
      return "account launch";
    }
  }

  /** Result of a lease acquisition attempt. Exactly one field is non-null. */
  public record AcquireResult(LeaseHandle handle, Conflict conflict) {
    public boolean isSuccess() {
      return handle != null;
    }
  }

  private final ConcurrentHashMap<String, LeaseHandle> leases = new ConcurrentHashMap<>();

  /**
   * Attempts to acquire the local launch lease for the given resource. Returns a success containing
   * a new unique {@link LeaseHandle} or a sanitized conflict describing the current holder.
   *
   * <p>Any existing lease — even for the same account — produces a conflict. Same-account
   * deduplication is handled upstream by {@link BuilderGoalLaunchOperationService}.
   */
  public AcquireResult tryAcquire(
      String machineUuid, int executorPort, String accountKey, String ownerId) {
    String resourceKey = resourceKey(machineUuid, executorPort);
    LeaseHandle newHandle =
        new LeaseHandle(machineUuid, executorPort, accountKey, ownerId, UUID.randomUUID());
    LeaseHandle existing = leases.putIfAbsent(resourceKey, newHandle);
    if (existing != null) {
      logger.debug(
          "Local launch lease conflict for resource {}: requested account={} owner={}, held by account={} owner={}",
          resourceKey,
          accountKey,
          ownerId,
          existing.accountKey(),
          existing.ownerId());
      return new AcquireResult(null, Conflict.of(existing));
    }
    logger.debug(
        "Acquired local launch lease for resource {} account={} owner={}",
        resourceKey,
        accountKey,
        ownerId);
    return new AcquireResult(newHandle, null);
  }

  /**
   * Returns true if the supplied handle still owns the lease. Uses exact owner-token comparison so
   * a stale worker cannot pass a stale handle to bypass a newer owner.
   */
  public boolean holdsLease(LeaseHandle handle) {
    if (handle == null) {
      return false;
    }
    String resourceKey = resourceKey(handle.machineUuid(), handle.executorPort());
    LeaseHandle existing = leases.get(resourceKey);
    return existing != null && existing.ownerToken().equals(handle.ownerToken());
  }

  /**
   * Releases the lease only when the handle's owner token matches the current holder. A stale
   * worker calling this with an outdated handle is a no-op.
   */
  public void release(LeaseHandle handle) {
    if (handle == null) {
      return;
    }
    String resourceKey = resourceKey(handle.machineUuid(), handle.executorPort());
    leases.computeIfPresent(
        resourceKey,
        (key, existing) -> {
          if (existing.ownerToken().equals(handle.ownerToken())) {
            logger.debug(
                "Released local launch lease for resource {} account={} owner={}",
                resourceKey,
                handle.accountKey(),
                handle.ownerId());
            return null;
          }
          logger.debug(
              "Stale lease release ignored for resource {}: requested owner={}, current owner={}",
              resourceKey,
              handle.ownerId(),
              existing.ownerId());
          return existing;
        });
  }

  static String resourceKey(String machineUuid, int executorPort) {
    return machineUuid + ":" + executorPort;
  }
}
