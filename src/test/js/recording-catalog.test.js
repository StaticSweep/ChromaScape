const {test} = require('node:test');
const assert = require('node:assert/strict');
const {JSDOM} = require('jsdom');
const catalog = require('../../main/resources/static/js/recording-catalog.js');
test('all cursor pages retain video-only recordings and exact account/task filters',async()=>{
 const urls=[];let count=0;
 const fetcher=async url=>{urls.push(url);return {ok:true,json:async()=>count++===0?{entries:[{recordingId:'video-only'}],hasMore:true,nextCursor:'opaque'}:{entries:[{recordingId:'tick-video'}],hasMore:false,nextCursor:null}};};
 const rows=await catalog.allPages(fetcher,'/api/recordings/v1/sessions',{accountName:'<owner>',taskKey:'FISHING_TRAINING'});
 assert.deepEqual(rows.map(x=>x.recordingId),['video-only','tick-video']);assert.match(urls[1],/cursor=opaque/);assert.match(urls[0],/accountName=%3Cowner%3E/);
});
test('malformed and repeated cursor cannot spin forever',async()=>{
 await assert.rejects(catalog.allPages(async()=>({ok:true,json:async()=>({entries:[],hasMore:true,nextCursor:'same'})}),'/catalog'),/Invalid|Repeated/);
 await assert.rejects(catalog.page(async()=>({ok:false,status:401}),'/catalog'),/401/);
});
test('account/task and all-account/task views preserve all recordings',()=>{
 const rows=[{recordingId:'1',accountName:'A',taskKey:'FISHING_TRAINING'},{recordingId:'2',accountName:'B',taskKey:'FISHING_TRAINING'},{recordingId:'3'}];
 assert.equal(catalog.groups(rows,'account').length,3);assert.equal(catalog.groups(rows,'task').length,2);
 assert.equal(catalog.groups(rows,'task')[0].entries.length,2);assert.match(catalog.groups(rows,'account')[2].label,/Unknown account.*Unknown task/);
});
test('settings drilldown distinguishes unknown and known empty without interpreting HTML',()=>{
 const dom=new JSDOM('<body></body>');const doc=dom.window.document;
 assert.match(catalog.settingsNode(doc,{taskArgs:null}).textContent,/Unknown/);
 assert.match(catalog.settingsNode(doc,{taskArgs:{}}).textContent,/\{\}/);
 const node=catalog.settingsNode(doc,{taskArgs:{target:'<img src=x onerror=alert(1)>'},settingsHash:'<script>bad</script>'});
 doc.body.appendChild(node);assert.equal(doc.querySelectorAll('img,script').length,0);assert.match(node.textContent,/onerror/);
});
test('video catalog and optional tick enrichment use distinct versioned cursor routes',()=>{
 const fs=require('node:fs');const html=fs.readFileSync(require('node:path').resolve(__dirname,'../../main/resources/templates/replay.html'),'utf8');
 assert.match(html,/\/api\/recordings\/v1\/sessions/);assert.match(html,/\/api\/replay\/playback\/v1\/recordings/);assert.doesNotMatch(html,/limit=200&offset=0/);
});
