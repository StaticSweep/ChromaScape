# FEDORA-03 rollback

FEDORA-03 adds a qualification harness and its evidence; it does not ship a new runtime payload.

## Runtime rollback

1. If a qualification runtime is still up, close the session and stop it:

   ```bash
   curl -X POST http://127.0.0.1:<port>/api/qualification/session/close
   kill -TERM -- -<runtime process group>
   ```

   The harness does this itself on every exit path.

2. Remove the private install prefix and the disposable database container:

   ```bash
   bash scripts/fedora/uninstall-tarball.sh --prefix <work>/prefix --bin-dir <work>/bin
   podman rm -f fedora03-pg-<pid>
   ```

3. Do not remove the operator's real user configuration, captures or recordings. The harness only
   ever writes under its own scratch root and the throwaway runtime's XDG directories.

## Native pair rollback

The locally wired pair stays out of the package. To remove it from an installed release, delete
`<prefix>/current/native/linux-x86_64` and re-run preflight; the loader then reports a missing
artifact instead of loading a stale one. No package file changes.

## Branch rollback

The delivery is additive (`scripts/qualification/fedora03_evidence.py`,
`scripts/qualification/ws_probe.py`, `scripts/fedora/certify-live-recording-debugger.sh`, fixtures,
docs, one Gradle task). Reverting the branch restores the FEDORA-02 behavior exactly; no database
migration, runtime configuration or shipped artifact depends on it.

## Failure containment

- A failed live check leaves the record with the exact `NOT_RUN` or `FAIL` case; the release gate
  refuses to mark the delivery complete, so a rollback never erases the evidence of what failed.
- No host security setting is modified by the harness; the only container-level flag is
  `--security-opt label=disable` for the native build container on an unlabeled mount, which does
  not change the host SELinux policy.
