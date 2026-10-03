# RemoteInput

![example workflow](https://github.com/Brandon-T/RemoteInput/actions/workflows/build.yml/badge.svg)

Remote input injects into a Java process to allow io operations such as keyboard and mouse input, reading canvas data and drawing on the canvas.

Binaries are available here:
https://github.com/Brandon-T/Reflection/releases/tag/autobuild

---

> [!NOTE]  
> Linux has protection against dll injection, `setcap` can be used on the executable injecting remoteinput.  
> `sudo setcap cap_sys_ptrace=eip /path/to/executable`
