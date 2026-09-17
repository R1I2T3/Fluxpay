// Read-only local smoke check. Never prints credentials, tokens or customer records.
const fs=require('node:fs');
const path=require('node:path');
const file=path.resolve(__dirname,'../../../.env');
const settings={...process.env};
if(fs.existsSync(file))for(const line of fs.readFileSync(file,'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z0-9_]+)=(.*)$/);if(m&&settings[m[1]]===undefined)settings[m[1]]=m[2];}
(async()=>{
  if(!settings.SEED_ADMIN_PASSWORD){console.log('SKIP: SEED_ADMIN_PASSWORD is not configured; no credentials guessed.');return;}
  const base='http://127.0.0.1:'+ (settings.SERVER_PORT||'8080');
  const login=await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:settings.SEED_ADMIN_EMAIL||'admin@local.fluxpay',password:settings.SEED_ADMIN_PASSWORD}),signal:AbortSignal.timeout(10000)});
  if(!login.ok){console.log('Admin login HTTP '+login.status+'; live smoke check blocked.');return;}
  const auth=(await login.json()).data;
  const token=auth.token;
  console.log('Authenticated role: '+auth.user.role+'; bearer token present: '+Boolean(token));
  for(const endpoint of ['/api/users/me','/api/policies','/api/compliance/cases?status=OPEN','/v3/api-docs']){
    const response=await fetch(base+endpoint,{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(10000)});
    const json=await response.json().catch(()=>({}));
    console.log(endpoint+': HTTP '+response.status+(response.ok?(endpoint==='/v3/api-docs'?' (policy API present: '+Boolean(json.paths?.['/api/policies'])+')':Array.isArray(json.data)?' ('+json.data.length+' records)':''):' '+(json.code||'')));
  }
})().catch(error=>{console.error('Live smoke check could not complete: '+error.message);process.exitCode=1;});
