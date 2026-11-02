#!/usr/bin/env python3
"""Record a RuneLite/micro6 game canvas on Linux (X11 or XWayland) for SC-2150 W12.

ChromaScape's own recorder captures the canvas HWND through user32 and only runs on Windows. This
is its Linux counterpart for the native round trip. It writes the same pair of files, so
`label_from_mouse_trace.py` reads them unchanged:

  * `<id>.mp4` - constant frame rate, exactly the canvas pixels (no title bar or sidebar);
  * `<id>.frames.jsonl` - `chromascape-recording-frames/v1`: a header with `startEpochMs`,
    `startNanos` and `fps`, then one line per encoded frame with `captureNanos` and `duplicate`.

RuneLite's game canvas is a heavyweight `java.awt.Canvas`, which on X11 has its own child window.
It is found as the single viewable window whose size equals `--canvas WxH` (the `canvas` value in
micro6's mouse trace), or named with `--window`, and grabbed with XGetImage, so no screen offset is
guessed. `captureNanos` is CLOCK_MONOTONIC (`time.monotonic_ns`) taken right after each grab
completes, the clock `System.nanoTime()` uses on Linux, as the Windows recorder stamps its frames.
Frames follow a fixed `--fps` schedule; when a grab overruns its slot, the missed slots repeat the
previous image with `duplicate: true` (CFR fill, as RecordingService does).

Stops after `--duration` seconds, on SIGINT/SIGTERM, or when `--stop-file` appears. Read-only with
respect to the client: it never sends input and never changes window state.
"""

import argparse
import ctypes
import ctypes.util
import json
import os
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path

SCHEMA = "chromascape-recording-frames/v1"
IS_VIEWABLE = 2
Z_PIXMAP = 2
ALL_PLANES = ctypes.c_ulong(0xFFFFFFFF)

Window = ctypes.c_ulong


class XWindowAttributes(ctypes.Structure):
    _fields_ = [("x", ctypes.c_int), ("y", ctypes.c_int), ("width", ctypes.c_int),
                ("height", ctypes.c_int), ("border_width", ctypes.c_int), ("depth", ctypes.c_int),
                ("visual", ctypes.c_void_p), ("root", Window), ("class_", ctypes.c_int),
                ("bit_gravity", ctypes.c_int), ("win_gravity", ctypes.c_int),
                ("backing_store", ctypes.c_int), ("backing_planes", ctypes.c_ulong),
                ("backing_pixel", ctypes.c_ulong), ("save_under", ctypes.c_int),
                ("colormap", ctypes.c_ulong), ("map_installed", ctypes.c_int),
                ("map_state", ctypes.c_int), ("all_event_masks", ctypes.c_long),
                ("your_event_mask", ctypes.c_long), ("do_not_propagate_mask", ctypes.c_long),
                ("override_redirect", ctypes.c_int), ("screen", ctypes.c_void_p)]


class XImageFuncs(ctypes.Structure):
    _fields_ = [("create_image", ctypes.c_void_p),
                ("destroy_image", ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p)),
                ("get_pixel", ctypes.c_void_p), ("put_pixel", ctypes.c_void_p),
                ("sub_image", ctypes.c_void_p), ("add_pixel", ctypes.c_void_p)]


class XImage(ctypes.Structure):
    _fields_ = [("width", ctypes.c_int), ("height", ctypes.c_int), ("xoffset", ctypes.c_int),
                ("format", ctypes.c_int), ("data", ctypes.c_void_p), ("byte_order", ctypes.c_int),
                ("bitmap_unit", ctypes.c_int), ("bitmap_bit_order", ctypes.c_int),
                ("bitmap_pad", ctypes.c_int), ("depth", ctypes.c_int),
                ("bytes_per_line", ctypes.c_int), ("bits_per_pixel", ctypes.c_int),
                ("red_mask", ctypes.c_ulong), ("green_mask", ctypes.c_ulong),
                ("blue_mask", ctypes.c_ulong), ("obdata", ctypes.c_void_p), ("f", XImageFuncs)]


class XClassHint(ctypes.Structure):
    _fields_ = [("res_name", ctypes.c_void_p), ("res_class", ctypes.c_void_p)]


def load_xlib():
    path = ctypes.util.find_library("X11") or "libX11.so.6"
    x = ctypes.CDLL(path)
    x.XOpenDisplay.restype = ctypes.c_void_p
    x.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x.XDefaultRootWindow.restype = Window
    x.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
    x.XQueryTree.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(Window),
                             ctypes.POINTER(Window), ctypes.POINTER(ctypes.POINTER(Window)),
                             ctypes.POINTER(ctypes.c_uint)]
    x.XGetWindowAttributes.argtypes = [ctypes.c_void_p, Window,
                                       ctypes.POINTER(XWindowAttributes)]
    x.XGetImage.restype = ctypes.POINTER(XImage)
    x.XGetImage.argtypes = [ctypes.c_void_p, Window, ctypes.c_int, ctypes.c_int, ctypes.c_uint,
                            ctypes.c_uint, ctypes.c_ulong, ctypes.c_int]
    x.XGetClassHint.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(XClassHint)]
    x.XFree.argtypes = [ctypes.c_void_p]
    x.XCloseDisplay.argtypes = [ctypes.c_void_p]
    return x


def windows(x, display, root):
    """Every window below root with its ancestors (outermost first), depth first."""
    stack = [(root, ())]
    while stack:
        window, ancestors = stack.pop()
        root_ret, parent, children, count = Window(), Window(), ctypes.POINTER(Window)(), \
            ctypes.c_uint()
        if not x.XQueryTree(display, window, ctypes.byref(root_ret), ctypes.byref(parent),
                            ctypes.byref(children), ctypes.byref(count)):
            continue
        kids = [children[i] for i in range(count.value)]
        if children:
            x.XFree(children)
        path = ancestors + ((window,) if window != root else ())
        for kid in kids:
            yield kid, path
            stack.append((kid, path))


def class_hint(x, display, window):
    hint = XClassHint()
    if not x.XGetClassHint(display, window, ctypes.byref(hint)):
        return ""
    values = [v for v in (hint.res_name, hint.res_class) if v]
    text = " ".join(ctypes.string_at(v).decode("utf-8", "replace") for v in values)
    for value in values:
        x.XFree(value)
    return text


def find_canvas(x, display, root, width, height):
    """Viewable windows of exactly width x height with no such window inside them.

    A Java frame nests the heavyweight canvas inside a same-size container, so only the innermost
    match is the canvas. The owner is the nearest WM_CLASS that is not the window manager's frame.
    """
    found = []
    for window, ancestors in windows(x, display, root):
        attributes = XWindowAttributes()
        if not x.XGetWindowAttributes(display, window, ctypes.byref(attributes)):
            continue
        if (attributes.width, attributes.height) == (width, height) \
                and attributes.map_state == IS_VIEWABLE:
            found.append((window, ancestors))
    inner = [(w, a) for w, a in found if not any(w in other for _, other in found)]
    result = []
    for window, ancestors in inner:
        owner = ""
        for candidate in (window,) + tuple(reversed(ancestors)):
            hint = class_hint(x, display, candidate)
            if hint and "mutter-x11-frames" not in hint:
                owner = hint
                break
        result.append((window, ancestors[0] if ancestors else window, owner))
    return result


def list_viewable(x, display, root, min_width=200, min_height=200):
    """Every viewable window at least min_width x min_height, with its size and class hint.

    Used to find the game canvas before a session: the recorder needs an exact size, and that
    size is read off this listing.
    """
    listed = []
    for window, ancestors in windows(x, display, root):
        attributes = XWindowAttributes()
        if not x.XGetWindowAttributes(display, window, ctypes.byref(attributes)):
            continue
        if attributes.map_state != IS_VIEWABLE:
            continue
        if attributes.width < min_width or attributes.height < min_height:
            continue
        owner = ""
        for candidate in (window,) + tuple(reversed(ancestors)):
            hint = class_hint(x, display, candidate)
            if hint and "mutter-x11-frames" not in hint:
                owner = hint
                break
        listed.append((window, attributes.width, attributes.height, owner))
    return listed


def grab(x, display, window, width, height):
    image = x.XGetImage(display, window, 0, 0, width, height, ALL_PLANES, Z_PIXMAP)
    completed = time.monotonic_ns()
    if not image:
        raise RuntimeError("XGetImage failed (window unmapped, resized or off-screen?)")
    try:
        contents = image.contents
        if contents.bits_per_pixel != 32:
            raise RuntimeError(f"unsupported {contents.bits_per_pixel} bits per pixel")
        row = contents.bytes_per_line
        raw = ctypes.string_at(contents.data, row * height)
        if row != width * 4:
            raw = b"".join(raw[y * row:y * row + width * 4] for y in range(height))
        return raw, completed
    finally:
        image.contents.f.destroy_image(ctypes.cast(image, ctypes.c_void_p))


class XShmSegmentInfo(ctypes.Structure):
    _fields_ = [("shmseg", ctypes.c_ulong), ("shmid", ctypes.c_int),
                ("shmaddr", ctypes.c_void_p), ("readOnly", ctypes.c_int)]


class ShmGrabber:
    """MIT-SHM grab into one shared segment: the server writes the pixels straight into memory
    this process maps, instead of streaming them over the X socket (about 14 ms for 765x503 on
    XWayland, against about 1 ms here), which keeps the capture instant well inside one sample."""

    IPC_PRIVATE, IPC_CREAT, IPC_RMID = 0, 0o1000, 0

    def __init__(self, x, display, window, width, height):
        xext = ctypes.CDLL(ctypes.util.find_library("Xext") or "libXext.so.6")
        libc = ctypes.CDLL(ctypes.util.find_library("c"), use_errno=True)
        xext.XShmQueryExtension.argtypes = [ctypes.c_void_p]
        xext.XShmCreateImage.restype = ctypes.POINTER(XImage)
        xext.XShmCreateImage.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint,
                                         ctypes.c_int, ctypes.c_void_p,
                                         ctypes.POINTER(XShmSegmentInfo), ctypes.c_uint,
                                         ctypes.c_uint]
        xext.XShmAttach.argtypes = [ctypes.c_void_p, ctypes.POINTER(XShmSegmentInfo)]
        xext.XShmDetach.argtypes = [ctypes.c_void_p, ctypes.POINTER(XShmSegmentInfo)]
        xext.XShmGetImage.argtypes = [ctypes.c_void_p, Window, ctypes.POINTER(XImage),
                                      ctypes.c_int, ctypes.c_int, ctypes.c_ulong]
        libc.shmget.argtypes = [ctypes.c_int, ctypes.c_size_t, ctypes.c_int]
        libc.shmat.restype = ctypes.c_void_p
        libc.shmat.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
        libc.shmdt.argtypes = [ctypes.c_void_p]
        libc.shmctl.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        x.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
        if not xext.XShmQueryExtension(display):
            raise RuntimeError("MIT-SHM not available")
        attributes = XWindowAttributes()
        x.XGetWindowAttributes(display, window, ctypes.byref(attributes))
        self.x, self.xext, self.libc = x, xext, libc
        self.display, self.window, self.width, self.height = display, window, width, height
        self.info = XShmSegmentInfo()
        self.image = xext.XShmCreateImage(display, attributes.visual, attributes.depth, Z_PIXMAP,
                                          None, ctypes.byref(self.info), width, height)
        if not self.image or self.image.contents.bits_per_pixel != 32:
            raise RuntimeError("XShmCreateImage failed or not 32 bits per pixel")
        size = self.image.contents.bytes_per_line * height
        self.info.shmid = libc.shmget(self.IPC_PRIVATE, size, self.IPC_CREAT | 0o600)
        if self.info.shmid < 0:
            raise RuntimeError(f"shmget failed: errno {ctypes.get_errno()}")
        self.info.shmaddr = libc.shmat(self.info.shmid, None, 0)
        self.image.contents.data = self.info.shmaddr
        self.info.readOnly = 0
        if not xext.XShmAttach(display, ctypes.byref(self.info)):
            raise RuntimeError("XShmAttach failed")
        x.XSync(display, 0)
        # Marked for removal now; it lives until both sides detach, so nothing leaks on a crash.
        libc.shmctl(self.info.shmid, self.IPC_RMID, None)
        self.size = size

    def __call__(self):
        if not self.xext.XShmGetImage(self.display, self.window, self.image, 0, 0,
                                      ALL_PLANES.value):
            raise RuntimeError("XShmGetImage failed (window unmapped, resized or off-screen?)")
        completed = time.monotonic_ns()
        raw = ctypes.string_at(self.info.shmaddr, self.size)
        row = self.image.contents.bytes_per_line
        if row != self.width * 4:
            raw = b"".join(raw[y * row:y * row + self.width * 4] for y in range(self.height))
        return raw, completed

    def close(self):
        self.xext.XShmDetach(self.display, ctypes.byref(self.info))
        self.x.XSync(self.display, 0)
        self.libc.shmdt(self.info.shmaddr)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out-dir", type=Path,
                        help="recording output directory (required unless --list)")
    parser.add_argument("--id", help="recording id (default: x11-<uuid>)")
    parser.add_argument("--canvas", help="canvas size WxH, e.g. 765x503 (from the mouse trace)")
    parser.add_argument("--window", help="X window id of the canvas (hex or decimal)")
    parser.add_argument("--display", default=os.environ.get("DISPLAY", ":0"))
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--duration", type=float, default=600.0, help="seconds (default 600)")
    parser.add_argument("--stop-file", type=Path)
    parser.add_argument("--encoder", default="libopenh264")
    parser.add_argument("--bitrate", default="8M")
    parser.add_argument("--list", action="store_true", help="list candidate canvases and exit")
    parser.add_argument("--wait", type=float, default=0.0,
                        help="seconds to wait for exactly one matching canvas (default 0)")
    args = parser.parse_args()

    x = load_xlib()
    display = x.XOpenDisplay(args.display.encode())
    if not display:
        raise SystemExit(f"cannot open display {args.display}")
    root = x.XDefaultRootWindow(display)
    if args.list and not args.canvas and not args.window:
        for window, width, height, owner in list_viewable(x, display, root):
            print(f"0x{window:x} {width}x{height} class={owner!r}")
        return
    if not args.list and args.out_dir is None:
        raise SystemExit("--out-dir is required to record")
    if args.window:
        window = int(args.window, 0)
        attributes = XWindowAttributes()
        if not x.XGetWindowAttributes(display, window, ctypes.byref(attributes)):
            raise SystemExit(f"no window {args.window}")
        width, height = attributes.width, attributes.height
        owner = ""
    else:
        if not args.canvas:
            raise SystemExit("give --canvas WxH or --window")
        width, height = (int(v) for v in args.canvas.lower().split("x"))
        candidates = find_canvas(x, display, root, width, height)
        give_up = time.monotonic() + args.wait
        while len(candidates) != 1 and not args.list and time.monotonic() < give_up:
            time.sleep(0.5)
            candidates = find_canvas(x, display, root, width, height)
        if args.list:
            for window, top, owner in candidates:
                print(f"0x{window:x} top=0x{top:x} class={owner!r}")
            return
        if len(candidates) != 1:
            listing = ", ".join(f"0x{w:x} ({o})" for w, _, o in candidates) or "none"
            raise SystemExit(f"expected one viewable {width}x{height} window, found: {listing}; "
                             "pass --window")
        window, _, owner = candidates[0]

    try:
        grabber, method = ShmGrabber(x, display, window, width, height), "x11-shm"
    except (RuntimeError, OSError) as error:
        print(f"MIT-SHM unavailable ({error}); falling back to XGetImage", file=sys.stderr)
        grabber, method = (lambda: grab(x, display, window, width, height)), "x11-xgetimage"

    recording_id = args.id or f"x11-{uuid.uuid4()}"
    args.out_dir.mkdir(parents=True, exist_ok=True)
    video = args.out_dir / f"{recording_id}.mp4"
    sidecar = args.out_dir / f"{recording_id}.frames.jsonl"
    encoder = subprocess.Popen(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo",
         "-pix_fmt", "bgr0", "-s", f"{width}x{height}", "-r", str(args.fps), "-i", "-",
         "-c:v", args.encoder, "-b:v", args.bitrate, "-pix_fmt", "yuv420p",
         "-r", str(args.fps), str(video)],
        stdin=subprocess.PIPE)

    stopping = []
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stopping.append(True))

    interval = 1_000_000_000 // args.fps
    start_epoch_ms = time.time_ns() // 1_000_000
    start_nanos = time.monotonic_ns()
    frames = duplicates = 0
    previous = None
    with sidecar.open("w", encoding="utf-8") as out:
        out.write(json.dumps({"schema": SCHEMA, "kind": "header", "recordingId": recording_id,
                              "startEpochMs": start_epoch_ms, "startNanos": start_nanos,
                              "fps": args.fps, "ptsMicros": "frame * 1000000 / fps",
                              "capture": method, "window": f"0x{window:x}",
                              "windowClass": owner, "size": [width, height]},
                             separators=(",", ":")) + "\n")
        deadline = start_nanos + int(args.duration * 1e9)
        while not stopping and time.monotonic_ns() < deadline \
                and not (args.stop_file and args.stop_file.exists()):
            slot = start_nanos + frames * interval
            wait = slot - time.monotonic_ns()
            if wait > 0:
                time.sleep(wait / 1e9)
            requested = time.monotonic_ns()
            raw, captured = grabber()
            encoder.stdin.write(raw)
            out.write(f'{{"frame":{frames},"captureNanos":{captured},"duplicate":false,'
                      f'"requestNanos":{requested}}}\n')
            frames += 1
            previous = (raw, captured)
            # Slots that passed while grabbing repeat this image (CFR fill).
            while start_nanos + frames * interval + interval <= time.monotonic_ns():
                encoder.stdin.write(previous[0])
                out.write(f'{{"frame":{frames},"captureNanos":{previous[1]},"duplicate":true}}\n')
                frames += 1
                duplicates += 1
    encoder.stdin.close()
    code = encoder.wait()
    if hasattr(grabber, "close"):
        grabber.close()
    x.XCloseDisplay(display)
    summary = {"recordingId": recording_id, "video": str(video), "sidecar": str(sidecar),
               "window": f"0x{window:x}", "windowClass": owner, "size": [width, height],
               "capture": method, "fps": args.fps, "frames": frames, "duplicates": duplicates,
               "encoderExit": code}
    (args.out_dir / f"{recording_id}.capture.json").write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary))
    if code != 0:
        sys.exit(code)


if __name__ == "__main__":
    main()
