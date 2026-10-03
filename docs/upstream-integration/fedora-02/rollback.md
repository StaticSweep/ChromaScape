# FEDORA-01/02 rollback

## Package rollback

The tarball install is versioned and reversible without rebuilding:

```bash
scripts/fedora/install-tarball.sh --rollback <previous-version> \
  --prefix "$HOME/.local/opt/chromascape" --bin-dir "$HOME/.local/bin"
```

`current` is a symlink; a rollback flips it to the already-installed release. No running release is
ever overwritten, so a rollback never competes with a live process. To remove the package entirely:

```bash
scripts/fedora/uninstall-tarball.sh \
  --prefix "$HOME/.local/opt/chromascape" --bin-dir "$HOME/.local/bin"
```

The uninstaller removes only the recorded layout (shim, `current`, every `releases/` entry, the state
file, the prefix) and then proves the prefix and shim are gone.

The RPM uses RPM's own transaction history:

```bash
sudo dnf downgrade chromascape-<previous-version>   # or: sudo dnf history undo last
sudo dnf remove chromascape
```

## Runtime rollback

Stop only an instance the wrapper owns, using the PID/start-tick/boot-ID fenced lifecycle:

```bash
scripts/fedora/chromascape-fedora.sh stop
```

For the packaged runtime, stop the process group started by the installer, then reinstall the
previous release and start it. No database migration added by packaging needs reverting: the
packaging layer adds no migration, no wire-protocol field and no host security change.

## Data and evidence impact

Certification evidence JSON is evidence only and can be archived independently of a rollback. The
disposable Postgres container the harness starts is removed on exit; a host that targets an external
database must not roll that database back as part of a package rollback.
