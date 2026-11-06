#!/usr/bin/env python3
"""Explain published cursor samples for one SC-2150 analysis.

Pages `GET /sources/{id}/analyses/{id}/observations` from a disposable loopback verification
server and prints, per sample, its ordinal, visibility, hotspot coordinate, confidence and the
bounded evidence keys the read API exposes (`rule`, `continuation`, `pass`, `score`, ...). It is
read-only: it never schedules analysis, never uploads and never decodes media.

Refuses anything that is not a loopback verification instance, and refuses the owner's 8090
service. Output goes to stdout, or to a JSON file under `build/` (never into `docs/`).
"""

import argparse
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

ALLOWED_HOSTS = ("127.0.0.1", "localhost", "::1")
REFUSED_PORTS = (8090,)


class Client:
    """Minimal read-only JSON client for the mouse-video API."""

    def __init__(self, base_url):
        self.base = base_url.rstrip("/") + "/api/debug/mouse-videos"

    def get(self, path, params, timeout=60):
        query = urllib.parse.urlencode(params)
        url = self.base + path + ("?" + query if query else "")
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            body = error.read()
            try:
                return error.code, json.loads(body)
            except ValueError:
                return error.code, {"message": body.decode("utf-8", "replace")}


def pages(client, path, params):
    """Yield every observation page, following the artifact-pinned cursor."""
    cursor = None
    while True:
        query = dict(params)
        if cursor:
            query["cursor"] = cursor
        status, page = client.get(path, query)
        if status != 200:
            raise SystemExit("observations read failed (%s): %s" % (status, page))
        yield page
        cursor = page.get("nextCursor")
        if not cursor:
            return


def in_range(ordinal, lower, upper):
    return (lower is None or ordinal >= lower) and (upper is None or ordinal <= upper)


def collect(pages_iter, lower, upper, kind="CURSOR_VISIBILITY"):
    """Flatten pages into rows inside the ordinal range, keeping artifact identity."""
    rows = []
    checksum = None
    available = False
    for page in pages_iter:
        available = bool(page.get("available"))
        checksum = checksum or page.get("artifactChecksum")
        for item in page.get("items", []):
            if kind and item.get("kind") != kind:
                continue
            try:
                ordinal = int(item["frameOrdinal"])
            except (KeyError, TypeError, ValueError):
                continue
            if not in_range(ordinal, lower, upper):
                continue
            rows.append(
                {
                    "ordinal": ordinal,
                    "visibility": item.get("visibility"),
                    "x": item.get("x"),
                    "y": item.get("y"),
                    "confidence": item.get("confidence"),
                    "evidence": item.get("evidence") or {},
                }
            )
    rows.sort(key=lambda row: row["ordinal"])
    return available, checksum, rows


def format_row(row):
    parts = [
        "%d" % row["ordinal"],
        str(row["visibility"]),
        "x=%s" % ("-" if row.get("x") is None else row["x"]),
        "y=%s" % ("-" if row.get("y") is None else row["y"]),
        "confidence=%s" % row.get("confidence"),
    ]
    for key, value in row["evidence"].items():
        parts.append("%s=%s" % (key, json.dumps(value)))
    return " ".join(parts)


def resolve_output(path_text):
    """Keep evidence JSON inside `build/`; nothing derived from video goes into `docs/`."""
    if not path_text:
        return None
    out = Path(path_text).resolve()
    build_root = (Path.cwd() / "build").resolve()
    if build_root not in out.parents and out != build_root:
        raise SystemExit("--out must be a file under the repository's build/ directory")
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="loopback verification server")
    parser.add_argument("--source", required=True, help="source id")
    parser.add_argument("--analysis", required=True, help="completed analysis id")
    parser.add_argument("--kind", default="CURSOR_VISIBILITY")
    parser.add_argument("--from", dest="lower", type=int, help="first ordinal (inclusive)")
    parser.add_argument("--to", dest="upper", type=int, help="last ordinal (inclusive)")
    parser.add_argument("--limit", type=int, default=500, help="observations per page")
    parser.add_argument("--out", help="write JSON under build/ instead of printing")
    args = parser.parse_args(argv)

    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.hostname not in ALLOWED_HOSTS or parsed.port in REFUSED_PORTS:
        raise SystemExit("a disposable loopback verification server is required")

    client = Client(args.base_url)
    path = "/sources/%s/analyses/%s/observations" % (args.source, args.analysis)
    available, checksum, rows = collect(
        pages(client, path, {"limit": args.limit, "kind": args.kind or ""}),
        args.lower,
        args.upper,
        args.kind,
    )
    document = {
        "schema": "sc2150-cursor-track-explain/v1",
        "sourceId": args.source,
        "analysisId": args.analysis,
        "artifactChecksum": checksum,
        "available": available,
        "kind": args.kind,
        "from": args.lower,
        "to": args.upper,
        "samples": len(rows),
        "items": rows,
    }
    out = resolve_output(args.out)
    if out is not None:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(document, indent=1))
        print(str(out))
        return 0
    print(
        "source=%s analysis=%s artifact=%s samples=%d"
        % (args.source, args.analysis, checksum, len(rows))
    )
    for row in rows:
        print(format_row(row))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
