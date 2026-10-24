import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdirSync,writeFileSync} from 'node:fs';
const [base,session,packageRoot,output,token]=process.argv.slice(2);
const require=createRequire(`${packageRoot}/client/package.json`),{chromium}=require('playwright');
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const context=await browser.newContext({viewport:{width:1280,height:800}}),page=await context.newPage();
const errors=[],sockets=[],writes=[],visible=[];
page.on('pageerror',error=>errors.push(error.message));
page.on('websocket',socket=>sockets.push(socket.url()));
page.on('request',request=>{if(request.method()!=='GET'&&request.url().includes('/api/xrsps/'))writes.push(request.url());});
async function observation(){return (await fetch(base+'/test/observation')).json();}
const timer=setInterval(()=>{void page.evaluate(()=>({state:window.osrsClient?.gameState,skill:window.osrsClient?.skillsMap?.get(10),banner:document.querySelector('[role=status]')?.textContent})).then(value=>{if(value.state===30&&value.skill&&JSON.stringify(value)!==JSON.stringify(visible.at(-1)))visible.push(value);}).catch(()=>{});},200);
try {
  await page.goto(`${base}/xrsps-client?session=${session}`,{waitUntil:'domcontentloaded'});
  await page.waitForFunction(()=>window.osrsClient?.gameState===30&&window.osrsClient?.widgetManager?.rootInterface===161,{},{timeout:120000});
  assert.equal(await page.evaluate(()=>window.osrsClient.skillsMap.get(10).baseLevel),20);
  const before=await observation();assert.equal(before.status,'READY');
  await page.mouse.wheel(0,150);await page.keyboard.press('ArrowLeft');
  assert.equal((await observation()).domainStateHash,before.domainStateHash);
  const started=await fetch(base+'/test/ai-run',{method:'POST',headers:{'X-Test-Control':token}});
  assert.equal(started.status,200,await started.text());
  const deadline=Date.now()+240000;
  let after;
  do {after=await observation();if(['SUCCEEDED','FAILED','CANCELED'].includes(after.status))break;await new Promise(resolve=>setTimeout(resolve,300));} while(Date.now()<deadline);
  assert.equal(after.status,'SUCCEEDED',JSON.stringify(after));
  assert.equal(after.fishingLevel,21);assert.ok(after.fishingXp>before.fishingXp);
  assert.equal(after.journalCount,1);assert.equal(after.receiptCount,1);
  assert.equal(after.taskVerified,true);assert.equal(after.journalReproduced,true);
  await page.waitForFunction(()=>window.osrsClient?.skillsMap?.get(10)?.baseLevel===21&&document.querySelector('[role=status]')?.textContent?.includes('SUCCEEDED'),{},{timeout:60000});
  const rendered=await page.evaluate(()=>({state:window.osrsClient.gameState,skill:window.osrsClient.skillsMap.get(10),root:window.osrsClient.widgetManager.rootInterface}));
  assert.equal(rendered.skill.xp,after.fishingXp);
  await page.screenshot({path:output+'/configured-ai-actual-client.png'});
  await page.reload({waitUntil:'domcontentloaded'});await page.waitForFunction(()=>window.osrsClient?.gameState===30&&window.osrsClient?.skillsMap?.get(10)?.baseLevel===21,{},{timeout:120000});
  assert.equal((await observation()).domainStateHash,after.domainStateHash);
  assert.ok(visible.some(frame=>frame.banner?.includes('RUNNING')),'No intermediate AI state was rendered');
  assert.deepEqual(sockets,[]);assert.deepEqual(writes,[]);assert.deepEqual(errors,[]);
  writeFileSync(output+'/configured-ai-client-result.json',JSON.stringify({before,after,rendered,visible,sockets,writes,errors},null,2)+'\n');
  console.log('Configured AI completed its native task while the actual OsrsClient rendered the same world.');
} catch(error){await page.screenshot({path:output+'/configured-ai-client-failure.png'}).catch(()=>{});throw error;}
finally{clearInterval(timer);await context.close();await browser.close();}
