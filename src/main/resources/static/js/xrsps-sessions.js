(() => {
  'use strict';
  const $=id=>document.getElementById(id),root='/api/xrsps/sessions';
  let selected=null,timer,busy=false,generation=0,last=null;
  const source=new URLSearchParams(location.search).get("scenario");if(source)$("scenario").value=source;
  function controls(){document.querySelectorAll("button").forEach(button=>button.disabled=busy);if(!last)return;const ai=last.mode==="AI_TASK";$("play").disabled=busy||ai;$("ai").disabled=busy||!ai||last.status!=="READY";$("seek-button").disabled=busy||ai;document.querySelector("[data-command=step-run]").disabled=busy||ai||last.status!=="PAUSED";document.querySelector("[data-command=recover]").disabled=busy||!["RUNNING","PAUSED","RECONCILING"].includes(last.status);}
  const id=()=>{const value=$('session').value.trim();if(!/^[0-9a-f-]{36}$/i.test(value))throw Error('Enter a valid session ID.');return value;};
  async function api(path,body){const response=await fetch(root+path,{credentials:'same-origin',method:body===undefined?'GET':'POST',headers:{Accept:'application/json',...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});const result=await response.json().catch(()=>null);if(!response.ok)throw Error(result?.message||`Request failed (${response.status})`);return result;}
  async function inspect(){
    if(!selected)return;const current=selected,version=++generation;
    try{
      const value=await api('/'+current);if(current!==selected||version!==generation)return;
      last=value;$('state').textContent=JSON.stringify(value,null,2);$('viewer').href='/xrsps-client?session='+encodeURIComponent(current);$('viewer').hidden=false;
      if(value.mode==='AI_TASK'){
        const task=await api('/'+current+'/ai-task');if(current!==selected||version!==generation)return;
        $('state').textContent+='\n'+JSON.stringify(task,null,2);
      }
      controls();
    }catch(error){if(version===generation)$('notice').textContent=error.message;}
    finally{if(current===selected&&version===generation)timer=setTimeout(inspect,1500);}
  }
  async function perform(action){if(busy)return;busy=true;document.querySelectorAll('button').forEach(button=>button.disabled=true);try{await action();$('notice').textContent='Session updated.';if(selected){clearTimeout(timer);await inspect();}}catch(error){$('notice').textContent=error.message;}finally{busy=false;controls();}}
  $('create').addEventListener('submit',event=>{event.preventDefault();void perform(async()=>{const result=await api('',{scenarioRef:$('scenario').value.trim(),mode:$('mode').value,idempotencyKey:crypto.randomUUID()});selected=result.id;$('session').value=selected;});});
  $('connect').onclick=()=>void perform(async()=>{selected=id();await api('/'+selected);});
  document.querySelectorAll('[data-command]').forEach(button=>button.onclick=()=>void perform(()=>{const command=button.dataset.command==='recover'&&last?.mode==='AI_TASK'?'ai-recover':button.dataset.command;return api('/'+id()+'/'+command,command==='step-run'?{ticks:1}:{});}));
  $('play').onclick=()=>void perform(()=>api('/'+id()+'/play-recording',{}));
  $('seek-button').onclick=()=>void perform(()=>api('/'+id()+'/seek',{tick:Number($('seek').value)}));
  $('ai').onclick=()=>void perform(()=>api('/'+id()+'/ai-task',{provider:$('provider').value,modelId:$('model').value.trim(),targetLevel:Number($('level').value),maxTicks:Number($('ticks').value),maxSeconds:Number($('seconds').value)}));
  window.addEventListener('pagehide',()=>{selected=null;generation++;clearTimeout(timer);});
})();
