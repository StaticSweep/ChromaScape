#!/usr/bin/env bash
# Build the actual OsrsClient inside a committed, self-contained worker package.
set -euo pipefail
[[ $# == 1 ]] || { echo 'Usage: scripts/build-sc1991-client.sh WORKER_PACKAGE' >&2; exit 1; }
package_root=$(realpath "$1")
git -C "$package_root" diff --quiet
git -C "$package_root" diff --cached --quiet
[[ -z ${XRSPS_ALLOW_DEV_IDENTITY:-} ]] || { echo 'Development identities are forbidden' >&2; exit 1; }
cd "$package_root/client"
PUBLIC_URL=/xrsps-client REACT_APP_CACHE_BASE_URL=/xrsps-client/caches/ GENERATE_SOURCEMAP=false DISABLE_ESLINT_PLUGIN=true NODE_OPTIONS=--max-old-space-size=8192 ./node_modules/.bin/craco build
node ../server/src/replay/worker-launcher.js --capabilities > build/worker-capabilities.json
node --input-type=module - "$package_root" <<'JS'
import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import {join,relative} from 'node:path';
import {createHash} from 'node:crypto';
const root=process.argv[2],build=join(root,'client/build');
const cap=JSON.parse(readFileSync(join(build,'worker-capabilities.json'),'utf8'));
const capabilities=cap.capabilities??cap;
if(!capabilities.certifiable || capabilities.presentationProtocol?.version!==1)throw Error('Certified production-sync worker is required');
function scan(root,dir,assets={}){for(const entry of readdirSync(dir,{withFileTypes:true})){
 const path=join(dir,entry.name),key=relative(root,path).replaceAll('\\','/');
 if(entry.isSymbolicLink())throw Error('Client assets must be self-contained');
 if(entry.isDirectory())scan(root,path,assets);else if(!['worker-capabilities.json','replay-client-manifest.json'].includes(key))assets[key]=createHash('sha256').update(readFileSync(path)).digest('hex');
}return assets;}
const caches=join(root,'server/caches');
writeFileSync(join(build,'replay-client-manifest.json'),JSON.stringify({version:1,...capabilities.xrsps,assets:scan(build,build),cacheAssets:scan(caches,caches)},null,2)+'\n');
JS
