#!/usr/bin/env python3
"""Verify that a Git tree reproduces a tested SC-2150 candidate manifest.

A candidate manifest (for example `build/sc2150-r28-candidate.json`) maps repository paths to
the `sha256:` of the bytes that were tested. The delivery bundle is only faithful if a clean
extraction of what will be delivered reproduces every one of those hashes; the working tree
matching is not enough, because untracked or unstaged files silently drop out of a commit.

The tree is either the index (`--index`, the default: what the next commit contains) or a
commit-ish (`--rev`, what was pushed). Blobs are read with `git cat-file`, so nothing is
written to the working tree. Exit status is 0 only when every manifest path is present with the
expected bytes.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def manifest_files(manifest_path):
    """Returns the manifest's path -> lowercase hex sha256 map, with POSIX separators."""
    document = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    files = document.get("files")
    if not isinstance(files, dict) or not files:
        raise ValueError("manifest has no files map")
    result = {}
    for path, digest in files.items():
        if not isinstance(digest, str) or not digest.startswith("sha256:"):
            raise ValueError(f"invalid digest for {path}")
        result[path.replace("\\", "/")] = digest[len("sha256:"):].lower()
    return result


def blob_id(repo, path, rev):
    """Returns the blob object id for path in the index (rev None) or in rev, or None."""
    if rev is None:
        out = subprocess.run(["git", "-C", str(repo), "ls-files", "-s", "--", path],
                             capture_output=True, text=True, check=True).stdout.split()
        return out[1] if len(out) >= 2 else None
    out = subprocess.run(["git", "-C", str(repo), "ls-tree", rev, "--", path],
                         capture_output=True, text=True, check=True).stdout.split()
    return out[2] if len(out) >= 3 and out[1] == "blob" else None


def blob_sha256(repo, blob):
    data = subprocess.run(["git", "-C", str(repo), "cat-file", "blob", blob],
                          capture_output=True, check=True).stdout
    return hashlib.sha256(data).hexdigest()


def verify(repo, manifest_path, rev=None):
    """Returns (matched, mismatches); each mismatch is (path, 'absent' or actual hex)."""
    expected = manifest_files(manifest_path)
    mismatches = []
    for path, digest in sorted(expected.items()):
        blob = blob_id(repo, path, rev)
        if blob is None:
            mismatches.append((path, "absent"))
            continue
        actual = blob_sha256(repo, blob)
        if actual != digest:
            mismatches.append((path, actual))
    return len(expected) - len(mismatches), mismatches


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("manifest", help="candidate manifest with a files path->sha256 map")
    parser.add_argument("--repo", default=".", help="repository root (default: .)")
    parser.add_argument("--rev", help="verify this commit-ish instead of the index")
    args = parser.parse_args(argv)
    matched, mismatches = verify(Path(args.repo), args.manifest, args.rev)
    tree = args.rev or "index"
    print(f"{tree}: {matched}/{matched + len(mismatches)} candidate files match")
    for path, actual in mismatches:
        print(f"  MISMATCH {path}: {actual if actual == 'absent' else 'sha256:' + actual}")
    return 0 if not mismatches else 1


if __name__ == "__main__":
    sys.exit(main())
