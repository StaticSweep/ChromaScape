import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdirSync,writeFileSync} from 'node:fs';
const [base,session,packageRoot,output,controlToken]=process.argv.slice(2);
const require=createRequire(`${packageRoot}/client/package.json`),{chromium}=require('playwright');
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const context=await browser.newContext({viewport:{width:1280,height:800}}),page=await context.newPage();
const errors=[],sockets=[],writes=[];
page.on('pageerror',error=>{errors.push(error.message);console.error('Browser error:',error.message);});
page.on('console',message=>{console.log('Client console:',message.type(),message.text().slice(0,600));});page.on('websocket',socket=>sockets.push(socket.url()));
let capturedPresentation=false;
page.on('response',async response=>{if(!capturedPresentation && response.url().includes('/presentation?')){capturedPresentation=true;writeFileSync(output+'/first-presentation.json',await response.text());}});
page.on('response',response=>{if(response.status()>=400)console.error('HTTP failure:',response.status(),response.url());});
page.on('request',request=>{if(request.method()!=='GET' && request.url().includes('/api/xrsps/'))writes.push(request.url());});
const diagnostics=setInterval(()=>{void page.evaluate(()=>({state:window.osrsClient?.gameState,renderer:window.osrsClient?.renderer?.running,body:document.body.innerText.slice(0,500)})).then(value=>console.log('Loading diagnostic:',JSON.stringify(value))).catch(()=>{});},15000);
async function observation(){return (await fetch(base+'/test/observation')).json();}
async function control(command){const response=await fetch(base+'/test/'+command,{method:'POST',headers:{'X-Test-Control':controlToken}});assert.equal(response.status,200,await response.text());}
try {
 await page.goto(`${base}/xrsps-client?session=${session}`,{waitUntil:'domcontentloaded'});
 await page.waitForFunction(()=>window.osrsClient?.controlledPlayerServerId>=0 && window.osrsClient?.widgetManager?.rootInterface>=0,{},{timeout:120000});
 await page.waitForFunction(()=>window.osrsClient?.gameState===30,{},{timeout:120000});
 const rendered=await page.evaluate(()=>{const client=window.osrsClient,index=client.playerEcs.getIndexForServerId(client.controlledPlayerServerId);return {gameState:client.gameState,root:client.widgetManager.rootInterface,playerId:client.controlledPlayerServerId,x:client.playerEcs.getX(index)>>7,y:client.playerEcs.getY(index)>>7,plane:client.playerEcs.getLevel(index)};});
 assert.equal(rendered.x,3080);assert.equal(rendered.y,3227);
 await page.screenshot({path:output+'/actual-osrs-client.png'});
 const before=await observation();
 await page.mouse.move(550,360);await page.mouse.wheel(0,200);await page.keyboard.press('ArrowLeft');
 await page.locator('canvas').first().click({position:{x:300,y:250},button:'right'});await page.waitForTimeout(1200);
 assert.equal((await observation()).domainStateHash,before.domainStateHash,'viewer camera/menu input changed domain');
 await control('advance');await page.waitForFunction(()=>document.querySelector('[role=status]')?.textContent?.includes('tick 10'),{},{timeout:60000});
 const after=await observation();assert.equal(after.tick,10);assert.ok(after.fishingXp>before.fishingXp);
 await page.reload({waitUntil:'domcontentloaded'});await page.waitForFunction(()=>window.osrsClient?.gameState===30,{},{timeout:120000});
 assert.equal((await observation()).domainStateHash,after.domainStateHash,'reconnect changed domain');
 await control('seek');await page.waitForFunction(()=>document.querySelector('[role=status]')?.textContent?.includes('tick 0'),{},{timeout:60000});
 assert.equal((await observation()).domainStateHash,before.domainStateHash,'verified seek changed the original tick-zero state');
 assert.deepEqual(sockets,[],'read-only attachment opened a gameplay socket');assert.deepEqual(writes,[],'viewer submitted gameplay commands');
 assert.deepEqual(errors,[],'actual client raised browser errors');
 writeFileSync(output+'/actual-client-result.json',JSON.stringify({rendered,before,after,sockets,writes,errors},null,2)+'\n');
 console.log('Actual OsrsClient rendered native packets; camera, reconnect and verified seek passed.');
} catch(error) {console.error('Client state:',await page.evaluate(()=>({body:document.body.innerText.slice(0,1500),state:window.osrsClient?.gameState})).catch(()=>null));await page.screenshot({path:output+'/actual-client-failure.png'}).catch(()=>{});writeFileSync(output+'/browser-errors.json',JSON.stringify(errors,null,2));throw error;}
finally {clearInterval(diagnostics);await context.close();await browser.close();}
