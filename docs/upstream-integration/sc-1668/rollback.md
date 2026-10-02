# SC-1668 rollback

## Code rollback

Revert the SC-1668 merge commit through normal Git review. Do not reset the integration branch and
do not remove the earlier SC-1662–SC-1667 merges. The SC-1668 production delta is limited to startup
AWT selection, removal of request-time AWT mutation, and the optional first-cycle script hook.

If only the first-cycle hook is incompatible with a script, revert its SC-1668 code commit; scripts
that do not override the hook have no behavior change. If display selection causes a host regression,
set `-Djava.awt.headless=true` or `false` explicitly while the revert is prepared; explicit operator
configuration always wins.

## Runtime rollback

On Fedora, stop only the owned instance:

```bash
scripts/fedora/chromascape-fedora.sh stop
```

The PID/start-time/boot-ID fence prevents signalling an unrelated reused PID. Disable attachment in
local configuration before restarting the previous revision.

On Windows, use the canonical restart runbook and checkout the previously reviewed integration
revision at `C:\Dev\IdeaProjects\ChromaScape`. Never delete user profiles or recordings as part of
rollback.

## Data and protocol impact

SC-1668 adds no database migration, wire-protocol field, packaged native library, or automatic host
security change. Qualification JSON and logs are evidence only and can be archived independently of
the runtime rollback.
