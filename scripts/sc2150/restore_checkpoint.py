#!/usr/bin/env python3
"""Restore only SC-2150 checkpoint changes, refusing operator edits; dry-run by default.

Both refs must be reviewed local partial-tree checkpoint refs, never branch names. This is
not a Git reset or a database rollback. Stop affected workers before applying a real rollback.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args])


def tree(ref):
    assert ref.startswith("refs/codex/sc-2150/")
    result = {}
    for entry in git("ls-tree", "-rz", "--full-tree", ref).split(b"\0"):
        if not entry:
            continue
        meta, name = entry.split(b"\t", 1)
        mode, kind, blob = meta.decode().split()
        path = name.decode()
        assert mode in ("100644", "100755") and kind == "blob"
        assert not PurePosixPath(path).is_absolute() and ".." not in PurePosixPath(path).parts
        result[path] = (mode, blob)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", required=True)
    parser.add_argument("--final-ref", required=True)
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    before, after = tree(args.baseline_ref), tree(args.final_ref)
    paths = sorted(p for p in before.keys() | after.keys() if before.get(p) != after.get(p))
    root = args.destination.resolve()
    changes, conflicts = [], []
    for name in paths:
        path = root / name
        assert path.resolve().is_relative_to(root), name
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent != root):
            conflicts.append(name + ": symlink")
            continue
        exists = path.is_file()
        actual = None
        if exists:
            content = path.read_bytes()
            actual = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
        expected = after.get(name)
        if (expected is None and path.exists()) or (expected is not None and (not exists or actual != expected[1] or bool(path.stat().st_mode & 0o111) != (expected[0] == "100755"))):
            conflicts.append(name)
        changes.append({"path": name, "action": "restore" if name in before else "remove"})
    if conflicts:
        print(json.dumps({"applied": False, "conflicts": conflicts}, indent=2))
        raise SystemExit(2)
    if args.apply:
        for change in changes:
            path = root / change["path"]
            if change["action"] == "remove":
                path.unlink()
            else:
                mode, blob = before[change["path"]]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(git("cat-file", "blob", blob))
                path.chmod(int(mode[-3:], 8))
    print(json.dumps({"applied": args.apply, "changes": changes}, indent=2))


if __name__ == "__main__":
    main()
