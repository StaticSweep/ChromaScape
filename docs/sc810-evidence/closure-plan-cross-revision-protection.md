# SC-810 — cross-revision protection proof (2026-09-12)

## What was being proved

That the running executor cannot be induced to serve, or to appear to serve, source belonging to
a **different revision** than the artifact actually running — in either direction:

- a consumer pinned to revision *A* must never be silently handed revision *B*'s content, and
- current content must never be servable under a *prior* revision's identity label.

## Why this corpus makes the test meaningful

The bundle root the executor is configured with
(`MICRO6_SOURCE_BUNDLE_ROOT=C:\Dev\SC810\package-20260910\source-bundle-root`) contains **seven
genuine historical revisions** of this codebase, each a complete, signed, `COMPLETE`-marked bundle
with its own manifest, `objects/` tree, snapshot id, artifact digest and build id:

| Bundle | Snapshot id | Artifact digest | Build id |
|---|---|---|---|
| `srcbundle_073725b58580eeb7d80e27cd` | `srcsnap_031001ca49f7e57319b944cc` | `sha256:8db7a474…` | `6e271ce40c…` |
| `srcbundle_0a49cff0d280eef221c929b9` | `srcsnap_03820e6cbd3fc56ca8a1ad4e` | `sha256:8db7a474…` | `6e271ce40c…` |
| `srcbundle_1e39de6c66af33be8ab47074` | `srcsnap_aa352ea69abfda3d0d785508` | `sha256:f855176a…` | `bb789cc52a…` |
| `srcbundle_1f0d63ec65164d6d5c7daa62` | `srcsnap_c1c1e808b1240a5dd23a97ec` | `sha256:12ef96ad…` | `0325bc64fd…` |
| `srcbundle_752bec0ca477b3a7752b31ec` | `srcsnap_644a15f83e51c392ce55b64c` | `sha256:bcba97b4…` | `9034445686…` |
| **`srcbundle_7c5226066eb527205273c425`** | **`srcsnap_1c1527f1843cf6e36d490e0c`** | **`sha256:9c5fe625…`** | **`04718fe3bf…`** |
| `srcbundle_a6242e2d6dd8bdc1ed1748a9` | `srcsnap_0af14d7e7094a58b899c7ae7` | `sha256:bfd29589…` | `91d213fdc6…` |

`current.json` names the bold row as the active revision. **This is the load-bearing point: the
six prior revisions are physically present, intact and signed, right beside the current one.**
Every refusal below is therefore a genuine refusal to serve data that is available on disk — not
a trivial 404 for something missing.

## The protection surface (as implemented)

Read from `Micro6BuilderExecutorServer`:

1. **Selector syntax validation** — `sourceQuery` (~line 5986) allows exactly six query keys and
   pattern-matches each value (`srcsnap_[0-9a-f]{24}`, `sha256:[0-9a-f]{64}`,
   `file_[0-9a-f]{16,64}`). Unknown key, duplicate key, or malformed value → `400`.
2. **Selector pin enforcement** — `verifyManifestSelector` (~line 5946) compares a supplied
   `source_snapshot_id` / `source_manifest_hash` (or legacy `manifest_hash`) against the live
   catalog; any mismatch → `409 SOURCE_BUILD_MISMATCH`.
3. **Snapshot routing** — `catalogForSnapshot` (~line 5888) serves only a snapshot id belonging to
   a loaded catalog; well-formed but foreign → `404 SOURCE_UNAVAILABLE`.
4. **File/symbol scoping** — a file id absent from *that* catalog → `404 SOURCE_FILE_NOT_FOUND`.
5. **Route-to-purpose binding** — applied *before* any selector decoding or catalog lookup
   (the code comments this deliberately, so a caller cannot use one purpose to probe another
   resource class for existence).

## Results — 50 live cases against the running executor, 50 correct

All executed against `http://127.0.0.1:8098` on the live, running, signed artifact
(`04718fe3bf…` / `sha256:9c5fe625…`) with a complete audit context and a fresh correlation id per
request.

### A. Positive controls (6/6)

| # | Case | Result |
|---|---|---|
| 1 | manifest, no pin | `200` |
| 2 | manifest + correct snapshot pin | `200` |
| 3 | manifest + correct manifest-hash pin | `200` |
| 4 | manifest + both correct pins | `200` |
| 5 | file read under current snapshot | `200` |
| 6 | symbols under current snapshot | `200` |

### B–C. Prior-revision pins on the manifest (12/12) — all `409 SOURCE_BUILD_MISMATCH`

All **six** prior snapshot ids and all **six** prior manifest hashes were pinned in turn. Every
one was refused with `409 SOURCE_BUILD_MISMATCH`. The executor did not silently serve its current
manifest to a consumer asking for an older revision.

### D–E. Prior-revision snapshot ids on file and symbol reads (9/9) — all `404 SOURCE_UNAVAILABLE`

A file id that **is valid in the current snapshot** was requested under each of the six prior
snapshot ids, and symbols under three of them. Every one refused. Current bytes cannot be
laundered under a prior revision's identity.

### F. Mixed and malformed selectors (8/8)

| # | Case | Expected | Got |
|---|---|---|---|
| 28 | correct snapshot pin + prior hash pin | `409` | `409 SOURCE_BUILD_MISMATCH` |
| 29 | prior snapshot pin + correct hash pin | `409` | `409 SOURCE_BUILD_MISMATCH` |
| 30 | current hash with `sha256:` prefix stripped | *(predicted 409)* | **`400 SOURCE_MANIFEST_INVALID`** |
| 31 | never-existing well-formed snapshot | `404` | `404 SOURCE_UNAVAILABLE` |
| 32 | malformed snapshot id (non-hex) | `400` | `400 SOURCE_MANIFEST_INVALID` |
| 33 | malformed snapshot id (too short) | `400` | `400 SOURCE_MANIFEST_INVALID` |
| 34 | unknown file id, current snapshot | `404` | `404 SOURCE_FILE_NOT_FOUND` |
| 35 | symbols `file_id` not in snapshot | `404` | `404 SOURCE_FILE_NOT_FOUND` |

Cases 28 and 29 matter individually: the two pins are enforced **independently**, so satisfying
one does not suppress the other.

**Case 30 is recorded as a corrected expectation, not a defect.** The proof predicted `409`; the
server returned `400`. Reading `sourceQuery` lines 6008-6009 shows why: the manifest-hash selector
is syntax-validated against `sha256:[0-9a-f]{64}` *before* any comparison, so a prefix-stripped
value never reaches `verifyManifestSelector`. The behaviour is **stricter** than predicted and
still fail-closed. The prediction was wrong; the implementation was not.

### H. Pin cannot be dropped or smuggled (9/9)

This section was added *after* case 30 revealed that selector parsing is a distinct layer. It
targets the highest-value bypass class: making the server **ignore** a pin rather than fail it.

| # | Case | Result |
|---|---|---|
| 40 | misspelled pin key (`snapshot_id=` prior) | `400` — **not** silently ignored |
| 41 | unknown extra param beside a correct pin | `400` |
| 42 | duplicate pin, prior **then** current | `400` — no last-wins override |
| 43 | duplicate pin, current **then** prior | `400` — no first-wins override |
| 44 | legacy alias `manifest_hash` = prior | `409 SOURCE_BUILD_MISMATCH` |
| 45 | legacy alias `manifest_hash` = current | `200` |
| 46 | empty pin value | `400` |
| 47 | uppercase-hex prior snapshot pin | `400` — no case-folding bypass |
| 48 | URL-encoded prior snapshot pin (`srcsnap%5F…`) | `409` — decoded, then compared |

Cases 40-43 are the ones that would have been genuinely dangerous. A parser that ignored unknown
keys would turn a typo'd pin into an unpinned request answered `200`; one that took last-wins on
duplicates would let an attacker append a correct pin after a forged one. Neither is possible
here. Case 48 confirms the comparison happens *after* percent-decoding, so encoding is not an
evasion either.

### I. Path traversal into a sibling bundle (2/2)

| # | Case | Result |
|---|---|---|
| 49 | encoded slash + `..` in the snapshot segment, targeting the current bundle from a prior one | `400` |
| 50 | `..%2F..%2Fbundle.json` in the file segment | `400` |

`decodeSourceSegment` rejects decoded `/`, `\` and NUL before pattern-matching, so the six sibling
bundle directories are not reachable by traversal.

### G. Purpose and token binding (4/4)

| # | Case | Result |
|---|---|---|
| 36 | symbol-read with the **metadata** token | `401 SOURCE_ACCESS_DENIED` |
| 37 | manifest-read with the **content** token | `401 SOURCE_ACCESS_DENIED` |
| 38 | file endpoint with `manifest-read` purpose | `422 SOURCE_AUDIT_CONTEXT_INVALID` |
| 39 | manifest endpoint with `file-read` purpose | `422 SOURCE_AUDIT_CONTEXT_INVALID` |

Case 36 was originally a failed case in this proof's own first run, because the test used the
metadata token for symbols. The code (lines 5814-5815) maps **only** `manifest-read` to the
metadata token; `file-read` and `symbol-read` both require the content token. The test was wrong;
the `401` was correct, and is retained above as a genuine positive result.

## The served revision is the running revision

| Field | Running `/health` | Served manifest | Match |
|---|---|---|---|
| artifact digest | `sha256:9c5fe625ff57807f4d059afbe756e0727c2daf323707c8891b6f754f686b8319` | same | ✅ |
| build id | `04718fe3bf0f86a27f8eb9d18d769f721748727d` | same | ✅ |
| snapshot id | `srcsnap_1c1527f1843cf6e36d490e0c` | same | ✅ |

The served manifest hashes to `10086419fce0d0c1903401874702ca2c56b2b0b2186d152836b7ac1ed9cb9820`,
which equals exactly one of the seven on-disk manifests — the current bundle's. All six prior
manifests hash differently, so no prior revision is being served under any condition tested.

## Honest limits of this proof

- **The six prior revisions carry identical task-source content.** All 34 file ids and all 34
  `content_hash` values are the same in every bundle; only `build`, `artifact_digest`, `build_id`,
  `signature`, `source_snapshot_id` and `source_manifest_hash` differ. What is proved is therefore
  **revision-identity binding** — the server cannot misrepresent which revision it is, and cannot
  serve under a foreign revision label. It is *not* demonstrated here that a cross-revision leak
  would have returned materially different source bytes, because in this corpus it would not have.
  The consequence that does bite is at the manifest level: a prior manifest attests to a
  *different artifact digest*, so a silent substitution would tell a consumer the running binary
  is `8db7a474…` when it is in fact `9c5fe625…`.
- **File ids are stable across revisions**, so a file id alone carries no revision information.
  The snapshot id is consequently the *only* thing scoping a file read to a revision, which is
  what makes cases D and 40-43 the load-bearing ones rather than incidental.
- **Audit-sink verification was not performed.** The code calls `sourceAudit(..., "FAILED_<CODE>")`
  on each rejection, but the packaged `logback.xml` pins the `accountbuilder` package to `ERROR`,
  so these entries were not observed in this run. That the audit record is actually *written* for
  each of the 44 rejections above is asserted by code inspection only, not demonstrated.
- **No new revision was generated for this test.** A true "serve revision B to a consumer pinned
  at A" scenario with divergent content would require building a bundle from modified source;
  that was not done, as it would dirty the worktree the bundle generator requires to be clean.
- Signing key remains the **development** key `sc810-dev-ed25519-611ab9dd95a518af`.

## Reproduction

Scripts used: `xrev.sh`, `xrev2.sh`, `xrev3.sh` (job scratch, not committed — they contain the
local dev bearer tokens). Each case is a single `curl` with `Authorization: Bearer <purpose
token>`, `X-Source-Actor: sc810-acceptance`, the purpose matching the route, a unique
`X-Correlation-ID`, and `X-Source-Environment: local-development`.
