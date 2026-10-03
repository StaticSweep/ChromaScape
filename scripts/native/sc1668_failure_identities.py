#!/usr/bin/env python3
"""SC-1668 failure-identity extraction (Phase I).

Reads Gradle JUnit XML result directories and emits one stable identity per
unsuccessful test:

    suite | test class | test method | failure-or-error | exception type | normalized first line

Normalization: every case-insensitive hexadecimal token of 8+ hex characters in
the first diagnostic line is replaced with ``<hex>``, so run-identifiers,
object hashes, database ids, and similar incidental values cannot make two
occurrences of the same failure look like different identities. Names,
messages, and exception types are otherwise preserved verbatim.

Identity sets are compared as sorted unique lists: ``--base``/``--candidate``
with ``--write-sets`` writes base/candidate files, or ``--delta`` against a
second directory writes introduced/resolved/unchanged sections.

Usage:
    sc1668_failure_identities.py --suite ordinary --dir <junit-xml-dir> \
        --out <identities.txt>
    sc1668_failure_identities.py --suite ordinary --dir <cand-dir> \
        --delta <base-identities.txt> --out <delta.txt>

Exit codes: 0 success, 2 usage error.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

HEX_RUN_RE = re.compile(r"[A-Fa-f0-9]{8,}")
UNSUCCESSFUL_TAGS = ("failure", "error")


def normalize_first_line(message: str) -> str:
    first = (message or "").strip().splitlines()
    line = first[0].strip() if first else "(no message)"
    return HEX_RUN_RE.sub("<hex>", line)


def short_class(classname: str) -> str:
    return (classname or "").rsplit(".", 1)[-1]


def identities_from_dir(suite: str, directory: str) -> list:
    identities = set()
    files = sorted(glob.glob(os.path.join(directory, "TEST-*.xml")))
    if not files:
        raise SystemExit(f"no TEST-*.xml files found under {directory}")
    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            for outcome in case:
                if outcome.tag not in UNSUCCESSFUL_TAGS:
                    continue
                kind = "failure" if outcome.tag == "failure" else "error"
                message = outcome.get("message") or ""
                text = outcome.text or ""
                first = normalize_first_line(message or text)
                identities.add(
                    " | ".join(
                        (
                            suite,
                            short_class(case.get("classname") or "(unknown)"),
                            case.get("name") or "(unknown)",
                            kind,
                            (outcome.get("type") or "(no exception type)").strip(),
                            first,
                        )
                    )
                )
    return sorted(identities)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--delta")
    args = parser.parse_args()

    candidate_ids = identities_from_dir(args.suite, args.dir)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    if not args.delta:
        with open(args.out, "w", encoding="utf-8") as handle:
            for identity in candidate_ids:
                handle.write(identity + "\n")
        print(f"IDENTITIES suite={args.suite} count={len(candidate_ids)} out={args.out}")
        return 0

    with open(args.delta, "r", encoding="utf-8") as handle:
        base_ids = {line.strip() for line in handle if line.strip()}
    candidate_set = set(candidate_ids)

    introduced = sorted(candidate_set - base_ids)
    resolved = sorted(base_ids - candidate_set)
    unchanged = sorted(base_ids & candidate_set)

    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(f"# suite: {args.suite}\n")
        handle.write(f"# base identities: {len(base_ids)}\n")
        handle.write(f"# candidate identities: {len(candidate_set)}\n\n")
        handle.write("== introduced identities ==\n")
        handle.write(
            "\n".join(introduced) if introduced else "(none)\n"
        )
        handle.write("\n\n== resolved identities ==\n")
        handle.write("\n".join(resolved) if resolved else "(none)\n")
        handle.write("\n\n== unchanged identities ==\n")
        handle.write("\n".join(unchanged) if unchanged else "(none)\n")
        handle.write("\n")

    print(
        f"DELTA suite={args.suite} base={len(base_ids)} candidate={len(candidate_set)} "
        f"introduced={len(introduced)} resolved={len(resolved)} unchanged={len(unchanged)} "
        f"out={args.out}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
