# SC-1664 input lifecycle contract

- A session binds one exact local `ProcessIdentity` and selection generation.
- Construction is side-effect free; only `openSelectedSession()` may attach.
- Linux attachment requires explicit selection, a current discovery match, an enabled feature flag,
  an explicitly resolvable SC-1662 artifact, bounded exact-PID client enumeration and a non-zero
  target handle.
- Native calls are serialized on one daemon executor. Timeout or caller interruption poisons the
  lane; it is never reused.
- One active lease is permitted per exact identity. A poisoned or cleanup-uncertain target retains
  its lease deliberately.
- Every input operation revalidates exact identity and exact generation.
- Session coordinates are client-relative pixels with origin `(0, 0)` and are converted once.
- Close releases held keys and mouse buttons before releasing the target/backend. Successful close
  is idempotent; a deterministic non-poisoning cleanup failure may be retried.
- Windows preserves KInput behavior behind the neutral contract and fails closed for unsupported
  wheel input.
- Remote injection may persist inside the target after host-side target release. No unload claim is
  made.

