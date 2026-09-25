# Native SC-968 candidate worker paths

The candidate worker runs in an isolated clone. Its source checkout, capture Contracts,
and Gradle dependency cache need not be siblings.

- `SC968_CAPTURE_CONTRACTS_DIR` selects the exact absolute Contracts `java` directory.
  The native acceptance driver binds this to its supplied `ContractsRoot`. An explicit
  missing, relative, or malformed path fails; it never silently selects another checkout.
  When unconfigured outside acceptance, the conventional sibling `contracts/java` layout
  remains the default.
- `SC967_GRADLE_USER_HOME` selects a read-only dependency-cache seed. The finish launcher
  defaults it from `GRADLE_USER_HOME`, or the Windows user's `.gradle` directory, and checks
  for an absolute path containing `caches/modules-2` before starting acceptance.
  Set it explicitly to use a different prepared cache.
- The worker still creates a private writable Gradle home, copies only the required wrapper
  runtime, uses `GRADLE_RO_DEP_CACHE`, and builds offline with its fixed compile, checkstyle,
  regression-test, JAR, and SBOM gates. No caller-provided shell commands are accepted.
- Detached linked worktrees are supported: after cloning the common local repository,
  the worker fetches exactly the approved 40-character commit and verifies checked-out HEAD.
  It does not create a source branch or substitute a moving branch tip.
- Stored, proposal-verified manifests are rechecked using the current contract's canonical
  checksum, nested commit/build identity, clean state, and repository-relative file paths.
  Every file is checked against its normalized content hash and size; traversal and paths
  escaping the private checkout are rejected. The legacy manifest schema is not accepted.

Regression coverage lives in `IsolatedPatchWorkerTest`, which the native acceptance gate
now includes, and `sc968-acceptance-contract.test.js`.

The September 6 run `astra-sc967-sc968-20260906T140500Z` completed its one-action baseline
but failed with `CAPTURE_CONTRACTS_NOT_FOUND` after human approval. That run remains FAIL.
Build-only diagnosis of its approved patch is not a substitute for fresh three-client
acceptance, registry verification, or SC-1498 evidence.
