import test from 'node:test';
import assert from 'node:assert/strict';
import {buildProfile,routingLink,autoroutingLink,obfuscate} from './app.mjs';

const input={name:'آزمایش',remoteDns:'https://dns.example/dns-query',domesticDns:'https://dns.example/dns-query',globalProxy:true,direct:'example.com\nexample.com',block:'geosite:ads',directIp:'192.0.2.0/24'};
test('routing exports UTF-8 JSON in the Android import format',()=>{
  const profile=buildProfile(input);
  assert.deepEqual(profile.DirectSites,['domain:example.com']);
  assert.deepEqual(profile.DirectIp,['192.0.2.0/24']);
  const link=routingLink(profile);
  assert.equal(JSON.parse(Buffer.from(link.split('/onadd/')[1],'base64').toString()).Name,input.name);
});
test('invalid addresses and unsafe URLs are rejected',()=>{
  assert.throws(()=>buildProfile({...input,directIp:'999.1.1.1'}));
  assert.throws(()=>buildProfile({...input,remoteDns:'javascript:alert(1)'}));
  assert.throws(()=>autoroutingLink('http://example.com/a'));
  assert.equal(autoroutingLink('https://example.com/r.json'),'drkozol://autorouting/onadd/https://example.com/r.json');
});
test('obfuscated links use unique IVs and do not expose the subscription',async()=>{
  const url='https://example.com/private-token';
  const a=await obfuscate(url,'Provider'),b=await obfuscate(url,'Provider');
  assert.notEqual(a,b);assert.ok(a.startsWith('drkozol://crypt1/'));assert.ok(!a.includes('private-token'));
});
