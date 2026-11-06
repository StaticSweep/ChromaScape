// Synthetic mechanics only. Actual Spring/PostgreSQL review session, dense 60-label reload and seal.
import { chromium, request as playwrightRequest, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
const origin=process.env.SC2150_BASE_URL, evidence=process.env.SC2150_EVIDENCE, policyPath=process.env.SC2150_POLICY_FILE;
const url=new URL(origin);assert.equal(url.hostname,'127.0.0.1');assert.ok(Number(url.port)>=18190&&Number(url.port)<=18199);assert.ok(evidence&&policyPath);
// A pending preview is a scheduled job, not a synchronous response. Give it a bounded allowance
// that matches the supported preview budget, and fail fast on a terminal job error instead of
// hiding it behind a longer wait.
const previewTimeoutMs=Number(process.env.SC2150_PREVIEW_TIMEOUT_MS||60000);
assert.ok(Number.isFinite(previewTimeoutMs)&&previewTimeoutMs>=5000&&previewTimeoutMs<=300000,'SC2150_PREVIEW_TIMEOUT_MS must be 5000..300000');
fs.mkdirSync(evidence,{recursive:true});const fixture=path.join(evidence,'dense-synthetic.mp4');
const encoders=execFileSync('ffmpeg',['-hide_banner','-encoders'],{encoding:'utf8',stdio:['ignore','pipe','ignore']});
execFileSync('ffmpeg',['-v','error','-nostdin','-y','-f','lavfi','-i','testsrc2=size=160x96:rate=30:duration=5.9','-threads','2','-c:v',encoders.includes('libx264')?'libx264':'libopenh264',fixture]);
const originalDigest='sha256:'+createHash('sha256').update(fs.readFileSync(fixture)).digest('hex');
fs.writeFileSync(policyPath,JSON.stringify({schema:'review-corpus/v1',sources:[{originalDigest,originGroupId:'synthetic-session-test',split:'heldout'}]}));
const api=origin+'/api/debug/mouse-videos';const http=await playwrightRequest.newContext({extraHTTPHeaders:{Origin:origin}});
async function get(route){const r=await http.get(api+route);assert.equal(r.status(),200,await r.text());return r.json();}
async function tryGet(route){const r=await http.get(api+route);return r.status()===200?r.json():{status:r.status(),body:await r.text()};}
async function post(route,data){const nonce=(await get('/write-nonce')).nonce;return http.post(api+route,{data,headers:{'X-Mouse-Video-Nonce':nonce}});}
async function previewFor(sourceId,analysisId,ordinal){
  const list=await tryGet('/sources/'+sourceId+'/analyses/'+analysisId+'/frame-previews');
  if(!Array.isArray(list.items))return {preview:null,diagnostics:list};
  const items=list.items.filter(item=>item.frameOrdinal===ordinal);
  const preview=items.length?items[items.length-1]:null;
  const job=preview&&preview.jobId?await tryGet('/jobs/'+preview.jobId):null;
  return {preview,job,diagnostics:null};
}
async function waitForPreview(page,sourceId,analysisId,index){
  const ordinal=Number(await page.locator('[data-frame-ordinal]').inputValue().catch(()=>NaN));
  const deadline=Date.now()+previewTimeoutMs;
  while(Date.now()<deadline){
    if(await page.locator('[data-preview-image]').isVisible())return;
    if(Number.isFinite(ordinal)){
      const state=await previewFor(sourceId,analysisId,ordinal);
      if(state.preview&&['FAILED','NEEDS_RECONCILIATION'].includes(state.preview.state)){
        throw new Error('Exact preview terminal failure at label '+(index+1)+' frame '+ordinal+': '+JSON.stringify(state));
      }
    }
    await page.waitForTimeout(250);
  }
  const state=Number.isFinite(ordinal)?await previewFor(sourceId,analysisId,ordinal):{preview:null,diagnostics:null};
  throw new Error('Exact preview did not become visible within '+previewTimeoutMs+'ms at label '+(index+1)+' frame '+ordinal+': '+JSON.stringify(state));
}
const browser=await chromium.launch();
try {
 const nonce=(await get('/write-nonce')).nonce;
 const uploaded=await http.post(api+'/imports/upload',{headers:{'X-Mouse-Video-Nonce':nonce,'X-Mouse-Video-Request-Id':randomUUID()},multipart:{metadata:{name:'meta.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify({declaredOrigin:'GENERATED',taskKey:'synthetic-dense-review',context:'Synthetic protocol mechanics'}))},file:{name:'synthetic.mp4',mimeType:'video/mp4',buffer:fs.readFileSync(fixture)}}});
 assert.equal(uploaded.status(),202,await uploaded.text());const receipt=await uploaded.json();
 await expect.poll(async()=>(await get('/jobs/'+receipt.jobId)).status,{timeout:150000}).toBe('SUCCEEDED');
 const source=await get('/sources/'+receipt.sourceId);const analysisId=source.selectedAnalysisId;
 const policy=await get('/sources/'+source.id+'/review-policy?analysisId='+analysisId);assert.equal(policy.enrolled,true);assert.deepEqual(policy.eligibleModes,['WITHOUT_SUGGESTIONS']);
 assert.equal((await http.get(api+'/sources/'+source.id+'/analyses/'+analysisId+'/observations')).status(),403);
 assert.equal((await http.get(api+'/sources/'+source.id+'/exports')).status(),403);
 assert.equal((await post('/sources/'+source.id+'/analyses',{requestId:randomUUID(),algorithmId:'visual/v1'})).status(),403);
 const page=await browser.newPage();page.setDefaultTimeout(60000);const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 const predictionResponses=[];page.on('response',r=>{if(r.url().includes('/observations')&&r.status()===200)predictionResponses.push(r.url());});
 await page.goto(origin+'/mouse/video?source='+source.id);
 await expect(page.locator('[data-review-blind-toggle]')).toBeChecked();
 await page.locator('[data-review-attestation]').check();
 await page.locator('[data-review-mode-toggle]').check();
 await expect(page.locator('[data-review-hidden]')).toBeEnabled();
 for(let i=0;i<60;i++) {
   await waitForPreview(page,source.id,analysisId,i);
   await page.locator('[data-review-hidden]').click();
   if((i+1)%10===0) console.log('Labelled',i+1);
 }
 await page.locator('[data-review-mode-toggle]').uncheck();
 await expect(page.locator('[data-review-mode-toggle]')).not.toBeChecked();
 await page.reload();await page.locator('[data-review-attestation]').check();await page.locator('[data-review-mode-toggle]').check();
 await expect(page.locator('[data-review-draft-count]')).toHaveText('60');
 assert.deepEqual(predictionResponses,[]);
 await page.locator('[data-review-seal]').click();
 await expect(page.locator('[data-review-mode-status]')).toContainText('session sealed');
 const exportLink=page.locator('[data-review-session-export]');await expect(exportLink).toBeVisible();
 const response=await http.get(origin+await exportLink.getAttribute('href'));assert.equal(response.status(),200);
 const raw=await response.body();const envelope=JSON.parse(raw);assert.equal(envelope.session.state,'SEALED');assert.equal(envelope.revision.corrections.length,60);
 const sessionId=envelope.session.id;
 assert.ok(raw.equals(await(await http.get(api+'/review-sessions/'+sessionId+'/export')).body()));
 const index=await get('/sources/'+source.id+'/analyses/'+analysisId+'/frame-index?limit=500');
 fs.writeFileSync(path.join(evidence,'sealed-export.json'),raw);fs.writeFileSync(path.join(evidence,'frame-index.json'),JSON.stringify(index));
 const exportArgs=['scripts/sc2150/labels_from_review_export.py','--session-export',path.join(evidence,'sealed-export.json'),'--verify-server',origin,'--frame-index',path.join(evidence,'frame-index.json'),'--labeller','synthetic-protocol-verifier','--labelling-protocol','without-suggestions','--source-size','160x96','--output-size','160x96','--split','heldout','--out',path.join(evidence,'labels.json')];
 execFileSync('python3',exportArgs);const labels=JSON.parse(fs.readFileSync(path.join(evidence,'labels.json')));assert.equal(labels.schema,'sc2150-fa12-labels/v3');assert.equal(labels.provenance.independent_protocol_verified,true);assert.equal(labels.labels.length,60);
 assert.deepEqual(errors,[]);
 const result={sourceId:source.id,analysisId,sessionId,denseLabels:60,reloadedLabels:60,sealed:true,immutableExport:true,convertedSchema:labels.schema,predictionResponsesBeforeSeal:predictionResponses.length,pageErrors:errors,fixtureOrigin:'GENERATED; synthetic protocol mechanics only, not held-out accuracy or human qualification'};
 fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
} finally {await browser.close();await http.dispose();}
