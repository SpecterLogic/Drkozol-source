// crypt1 public interoperability material (MIT); see ../../docs/CRYPT1-NOTICE.txt.
const publicKeyHex="f6d40ea0c8a8899d7c682d09ba0d4165dfe2b3dd45e6bb3e25cb233cf00c2462";
function httpsUrl(value){const u=new URL(value);if(u.protocol!=="https:"||u.username||u.password||value.length>8192)throw Error("نشانی معتبر HTTPS وارد کنید.");return value;}
function lines(value,ip=false){return [...new Set(value.split(/[\n,]+/).map(s=>s.trim()).filter(Boolean))].map(s=>{if(s.length>1024)throw Error("The rule exceeds the allowed length.");if(/^(?:geosite|geoip|domain|full|regexp|ext):/.test(s))return s;if(ip){const [a,b]=s.split('/');if(a.includes(':')){new URL('http://['+a+']/');if(b!==undefined&&(!/^\d+$/.test(b)||+b>128))throw Error('Invalid CIDR');}else if(!/^\d+\.\d+\.\d+\.\d+$/.test(a)||a.split('.').some(n=>+n>255)||(b!==undefined&&(!/^\d+$/.test(b)||+b>32)))throw Error('Invalid IP');return s;}if(/[\s/@?#]/.test(s))throw Error('Invalid domain');return 'domain:'+new URL('https://'+s).hostname;});}
export function buildProfile(data){
 const name=(data.name||'').trim();if(!name||name.length>128)throw Error('نام پروفایل را وارد کنید.');
 if(data.strategy && !['AsIs','IPIfNonMatch','IPOnDemand'].includes(data.strategy))throw Error('روش بررسی دامنه نامعتبر است.');
 const profile={Name:name,GlobalProxy:String(Boolean(data.globalProxy)),DomainStrategy:data.strategy||'IPIfNonMatch',RemoteDNSType:'DoH',RemoteDNSDomain:httpsUrl(data.remoteDns),DomesticDNSType:'DoH',DomesticDNSDomain:httpsUrl(data.domesticDns),FakeDNS:String(Boolean(data.fakeDns))};
 for(const [prefix,field] of [['Direct','direct'],['Proxy','proxy'],['Block','block']]){profile[prefix+'Sites']=lines(data[field]||'');profile[prefix+'Ip']=lines(data[field+'Ip']||'',true);if(profile[prefix+'Sites'].length>5000||profile[prefix+'Ip'].length>5000)throw Error('Too many rules.');}
 if(data.geoip)profile.Geoipurl=httpsUrl(data.geoip);if(data.geosite)profile.Geositeurl=httpsUrl(data.geosite);
 return profile;
}
function base64(bytes){let text='';for(const b of bytes)text+=String.fromCharCode(b);return btoa(text);}
export function routingLink(profile){const json=JSON.stringify(profile);if(json.length>700000)throw Error('The profile is too large.');return 'drkozol://routing/onadd/'+base64(new TextEncoder().encode(json));}
export function autoroutingLink(url){return 'drkozol://autorouting/onadd/'+httpsUrl(url);}
export async function obfuscate(url,name=''){
 httpsUrl(url);const data={};if(name.trim())data.n=name.trim().slice(0,128);data.url=url;data.v=1;
 const key=await crypto.subtle.importKey('raw',Uint8Array.from(publicKeyHex.match(/../g),h=>parseInt(h,16)),{name:'AES-GCM'},false,['encrypt']);
 const iv=crypto.getRandomValues(new Uint8Array(12));const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv},key,new TextEncoder().encode(JSON.stringify(data))));
 const wire=new Uint8Array(iv.length+ct.length);wire.set(iv);wire.set(ct,iv.length);
 return 'drkozol://crypt1/'+base64(wire).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
}
if(typeof document!=='undefined'){
 let currentJson=null;const output=document.querySelector('#link-output'),feedback=document.querySelector('#feedback');
 function result(link,json){currentJson=json;output.value=link;feedback.textContent='Link ready. Copy it or open it in DrKozol.';feedback.dataset.error='false';document.querySelector('#copy').disabled=false;document.querySelector('#download').disabled=!json;document.querySelector('#open-app').href=link;document.querySelector('#open-app').setAttribute('aria-disabled','false');document.querySelector('#json-details').hidden=!json;document.querySelector('#json-output').textContent=json?JSON.stringify(json,null,2):'';}
 function error(e){feedback.dataset.error='true';feedback.textContent=e.message||'Please check the entered values.';}
 document.querySelector('#routing-form').addEventListener('submit',e=>{e.preventDefault();try{const data=Object.fromEntries(new FormData(e.target));data.globalProxy=e.target.elements.globalProxy.checked;data.fakeDns=e.target.elements.fakeDns.checked;const json=buildProfile(data);result(routingLink(json),json);}catch(e){error(e);}});
 document.querySelector('#auto-form').addEventListener('submit',e=>{e.preventDefault();try{result(autoroutingLink(e.target.elements.source.value),null);}catch(e){error(e);}});
 document.querySelector('#crypt-form').addEventListener('submit',async e=>{e.preventDefault();try{result(await obfuscate(e.target.elements.subscription.value,e.target.elements.provider.value),null);}catch(e){error(e);}});
 document.querySelector('#copy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(output.value);feedback.textContent='Copied.';}catch{output.focus();output.select();feedback.textContent='Select and copy the link manually.';}});
 document.querySelector('#download').addEventListener('click',()=>{if(!currentJson)return;const url=URL.createObjectURL(new Blob([JSON.stringify(currentJson,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='drkozol-routing.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
}
