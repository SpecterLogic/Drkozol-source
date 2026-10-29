from pathlib import Path
import urllib.request,json,hashlib,zipfile,os,base64
def sha(data):return hashlib.sha256(data).hexdigest()
token=os.environ['GH_TOKEN'];ep='https://api.github.com/repos/SpecterLogic/Drkozol-source'
class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        n=super().redirect_request(req,fp,code,msg,headers,newurl)
        if n is not None:n.remove_header('Authorization')
        return n
opener=urllib.request.build_opener(Redirect())
def request(url,data=None,binary=False,method=None):
    h={'Authorization':'Bearer '+token,'Accept':'application/octet-stream' if binary else 'application/vnd.github+json','User-Agent':'DrKozol-native-verification'}
    if data is not None:h['Content-Type']='application/octet-stream'
    with opener.open(urllib.request.Request(url,headers=h,data=data,method=method),timeout=300) as r:
        b=r.read();return b if binary else (json.loads(b) if b else None)
r=request(ep+'/releases/'+os.environ['SOURCE_RELEASE_ID']);assert r['draft'] and r['tag_name']=='v1.5.1'
assets={a['name']:a for a in r['assets']}
unsigned=assets['DrKozol-1.5.1-unsigned.apk'];source=assets['DrKozol-source-1.5.1.zip']
assert unsigned['digest']=='sha256:6a55f48f2aa58a1c912adeb87f71b42fb6884d471eaa1e78549b8aa7be8e40b7'
assert source['digest']=='sha256:e646822dcbc62c11dce9ce3494dce4f3f0a86f66cb0708fb29fd76b240d12a23'
base=Path('base.apk').read_bytes();assert sha(base)=='c29827beb653be31a4bd0c7894f5fb6f379c9407107daacb2cb2877931fda1bc'
expected=json.loads(os.environ['WG_HASHES']);libraries={}
with zipfile.ZipFile('base.apk') as z:
    for abi,digest in expected.items():
        data=z.read('lib/'+abi+'/libwg-go.so');assert sha(data)==digest;libraries[abi]=data
p=Path('repaired-unsigned.apk');p.write_bytes(request(ep+'/releases/assets/'+str(unsigned['id']),binary=True));assert 'sha256:'+sha(p.read_bytes())==unsigned['digest']
with zipfile.ZipFile(p,'a',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for abi,data in libraries.items():
        name='lib/'+abi+'/libwg-go.so';assert name not in z.namelist();info=zipfile.ZipInfo(name);info.compress_type=zipfile.ZIP_DEFLATED;info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,data)
with zipfile.ZipFile(p) as z:
    assert len(z.namelist())==len(set(z.namelist())) and z.testzip() is None
    for abi in expected:
        for lib in ('libwg-go.so','libgojni.so','libdrkozol-awg-probe.so','libhev-socks5-tunnel.so','libhevsockstun.so'):assert 'lib/'+abi+'/'+lib in z.namelist()
old=Path('source.zip');old.write_bytes(request(ep+'/releases/assets/'+str(source['id']),binary=True));assert 'sha256:'+sha(old.read_bytes())==source['digest']
readme=base64.b64decode(request(ep+'/contents/README.md?ref='+os.environ['SOURCE_SNAPSHOT'])['content'])
out=Path('repaired-source.zip')
with zipfile.ZipFile(old) as previous,zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for entry in previous.infolist():
        data=previous.read(entry.filename)
        if entry.filename=='Drkozol/README.md':data=readme
        if entry.filename=='Drkozol/BUILD-INPUTS.json':
            metadata=json.loads(data);assert metadata['source_commit']==os.environ['TESTED_SOURCE_SNAPSHOT'];metadata['source_commit']=os.environ['SOURCE_SNAPSHOT']
            for abi,digest in expected.items():metadata['native_input_sha256']['V2rayNG/awg/src/main/jniLibs/'+abi+'/libwg-go.so']=digest
            data=json.dumps(metadata,indent=2).encode()
        z.writestr(entry,data)
    for abi,data in libraries.items():
        name='Drkozol/V2rayNG/awg/src/main/jniLibs/'+abi+'/libwg-go.so';assert name not in z.namelist();info=zipfile.ZipInfo(name);info.compress_type=zipfile.ZIP_DEFLATED;info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,data)
for item,path in [(unsigned,p),(source,out)]:
    request(ep+'/releases/assets/'+str(item['id']),method='DELETE')
    data=path.read_bytes();a=request(r['upload_url'].split('{')[0]+'?name='+item['name'],data=data)
    assert a['digest']=='sha256:'+sha(data) and a['size']==len(data);print('Verified complete native package:',item['name'],a['digest'])
