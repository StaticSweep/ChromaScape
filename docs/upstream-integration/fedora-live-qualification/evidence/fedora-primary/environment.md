# Fedora live qualification — primary host environment

| Item | Value |
|---|---|
| Host | Fedora Linux 44 (Workstation Edition) |
| Kernel | `6.19.10-300.fc44.x86_64` |
| Arch | x86_64 |
| Desktop session | GNOME / Wayland (`DISPLAY=:0` via XWayland) |
| Java | `openjdk 17.0.20.1 2026-08-18 LTS` (Corretto) |
| Client | Micro6 `microbot-1.8.9.3.jar`, `net.runelite.client.RuneLite`, RuneLite `1.11.8-SNAPSHOT` |
| Native library | `libRemoteInput.so` SHA-256 `b0c8d3655876dd23e931a9df48efc87b96f936758e5a18a27988d9ab49a5fefd` |
| Pair identity | `43b38e8f95d8f2dcf2d0ee4dd3ffb86d3cffd2fa975cca4db27970c4d1b03e9d` |
| Local patch identity | `sha256:a7744da1c54a5646b975d9b7adce5891832394b1d84104bb8009ade551107040` |
| Toolchain image | `localhost/sc1662-fedora-toolchain:latest` digest `sha256:15179d9fbd427cc73c799f4710631fdb37c2183dbedfe53c5a24cc758ea72af4` |

Absolute host paths and the invoking user are redacted. The native pair was built in the pinned
Fedora toolchain container with `--repeat-build`; the two builds were byte-identical.
