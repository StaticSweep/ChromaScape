#!/usr/bin/env bash
# Stage a committed authoritative worker for ChromaScape's native offline sandbox.
set -euo pipefail
[[ $(uname -s) == Linux ]] || { echo "Native Linux is required" >&2; exit 1; }
[[ $# == 2 ]] || { echo "Usage: $0 SOURCE_CHECKOUT NEW_PACKAGE_DIRECTORY" >&2; exit 1; }
source_root=$(realpath "$1")
package_root=$(realpath -m "$2")
[[ ! -e "$package_root" ]] || { echo "Package destination already exists" >&2; exit 1; }
git -C "$source_root" diff --quiet
git -C "$source_root" diff --cached --quiet
worker_commit=$(git -C "$source_root" rev-parse HEAD)
git clone --local --no-hardlinks --no-checkout "$source_root" "$package_root"
git -C "$package_root" checkout --detach "$worker_commit"
# Copy ignored dependencies/assets into the approved root; root symlinks are resolved first.
for component in node_modules server/node_modules client/node_modules server/caches server/cache; do
  component_root=$(realpath "$source_root/$component")
  [[ -d "$component_root" ]] || { echo "Missing component: $component" >&2; exit 1; }
  mkdir -p "$(dirname "$package_root/$component")"
  cp -a --reflink=auto "$component_root" "$package_root/$component"
done
node "$package_root/packages/replay-kernel/scripts/generate-runtime-identity.mjs"
node "$package_root/server/src/replay/worker-launcher.js" --capabilities
