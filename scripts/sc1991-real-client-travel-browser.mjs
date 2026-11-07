import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdirSync,writeFileSync} from 'node:fs';
const [base,session,packageRoot,output,token]=process.argv.slice(2);
const require=createRequire(`${packageRoot}/client/package.json`),{chromium}=require('playwright');
mkdirSync(output,{recursive:true});
const browser=await chromium.launch({headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const context=await browser.newContext({viewport:{width:1280,height:800}}),page=await context.newPage();
const errors=[],sockets=[],writes=[];
page.on('pageerror',error=>errors.push(error.message));page.on('websocket',socket=>sockets.push(socket.url()));
page.on('request',request=>{if(request.method()!=='GET'&&request.url().includes('/api/xrsps/'))writes.push(request.url());});
async function observation(){return (await fetch(base+'/test/observation')).json();}
async function position(){return page.evaluate(()=>{const c=window.osrsClient,index=c.playerEcs.getIndexForServerId(c.controlledPlayerServerId);return {x:c.playerEcs.getX(index)>>7,y:c.playerEcs.getY(index)>>7,plane:c.playerEcs.getLevel(index)};});}
try {
 await page.goto(`${base}/xrsps-client?session=${session}`,{waitUntil:'domcontentloaded'});
 await page.waitForFunction(()=>window.osrsClient?.gameState===30&&window.osrsClient?.controlledPlayerServerId>=0,{},{timeout:120000});
 const before=await observation();assert.equal((await position()).x,3080);
 const response=await fetch(base+'/test/travel',{method:'POST',headers:{'X-Test-Control':token}});assert.equal(response.status,200,await response.text());
 await page.waitForFunction(()=>{const c=window.osrsClient;if(!c||c.gameState!==30)return false;const i=c.playerEcs.getIndexForServerId(c.controlledPlayerServerId);return (c.playerEcs.getX(i)>>7)===3162&&(c.playerEcs.getY(i)>>7)===3480;},{},{timeout:120000});
 const after=await observation(),rendered=await position();assert.deepEqual(rendered,after.position);
 assert.equal(after.journalCount,2);assert.equal(after.receiptCount,2);assert.equal(after.status,'PAUSED');
 await page.screenshot({path:output+'/native-jewelry-destination.png'});
 await page.mouse.wheel(0,150);assert.equal((await observation()).domainStateHash,after.domainStateHash);
 await page.reload({waitUntil:'domcontentloaded'});await page.waitForFunction(()=>window.osrsClient?.gameState===30,{},{timeout:120000});
 assert.deepEqual(await position(),rendered);assert.equal((await observation()).domainStateHash,after.domainStateHash);
 assert.deepEqual(errors,[]);assert.deepEqual(sockets,[]);assert.deepEqual(writes,[]);
 writeFileSync(output+'/native-jewelry-client-result.json',JSON.stringify({before,after,rendered,errors,sockets,writes},null,2)+'\n');
}catch(error){await page.screenshot({path:output+'/native-jewelry-failure.png'}).catch(()=>{});throw error;}
finally{await context.close();await browser.close();}
