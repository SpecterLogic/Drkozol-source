from pathlib import Path
import os,sys,json,hashlib,urllib.request,urllib.parse,time,base64,gzip,zipfile,io,subprocess
def sha(data):return hashlib.sha256(data).hexdigest()
token=os.environ['GH_TOKEN'];apiroot='https://api.github.com';app=apiroot+'/repos/SpecterLogic/Drkozol';src=apiroot+'/repos/SpecterLogic/Drkozol-source'
class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        result=super().redirect_request(req,fp,code,msg,headers,newurl)
        if result is not None:result.remove_header('Authorization')
        return result
opener=urllib.request.build_opener(Redirect())
def request(url,method='GET',data=None,binary=False):
    h={'Authorization':'Bearer '+token,'Accept':'application/octet-stream' if binary else 'application/vnd.github+json','User-Agent':'DrKozol-Android-repair'}
    if isinstance(data,dict):data=json.dumps(data).encode();h['Content-Type']='application/json'
    elif data is not None:h['Content-Type']='application/octet-stream'
    for attempt in range(4):
        try:
            with opener.open(urllib.request.Request(url,headers=h,method=method,data=data),timeout=300) as r:
                value=r.read();return value if binary else (json.loads(value) if value else None)
        except Exception:
            if attempt==3:raise
            time.sleep(2)
def upload(release,name,data):
    old=next((a for a in release['assets'] if a['name']==name),None)
    if old:assert old['digest']=='sha256:'+sha(data);return old
    a=request(release['upload_url'].split('{')[0]+'?name='+urllib.parse.quote(name),'POST',data)
    assert a['digest']=='sha256:'+sha(data) and a['size']==len(data);return a
assert request(apiroot+'/user')['login']=='SpecterLogic'
cfg=json.loads(os.environ['REPAIR_CONFIG']);source=request(src+'/releases/'+str(cfg['source_release_id']));assert source['draft'] and source['tag_name']=='v1.5.1-android-r2'
release=request(app+'/releases/'+str(cfg['app_release_id']));assert not release['draft'] and release['tag_name']=='v1.5.1'
assert sha(release['body'].encode())==cfg['body_sha256']
assets={a['name']:a for a in release['assets']};old=assets['DrKozol-1.5.1.apk'];assert old['digest']=='sha256:'+cfg['base_sha256']
for name,digest in cfg['desktop'].items():assert assets[name]['digest']==digest
base=request(app+'/releases/assets/'+str(old['id']),binary=True);assert sha(base)==cfg['base_sha256']
part_assets={a['name']:a for a in source['assets']};chunks=[]
for part in cfg['overlay']['parts']:
    data=request(src+'/releases/assets/'+str(part_assets[part['name']]['id']),binary=True);assert sha(data)==part['sha256'];chunks.append(data)
patch_data=b''.join(chunks);assert sha(patch_data)==cfg['overlay']['sha256'];patch=json.loads(gzip.decompress(patch_data));assert patch['base_sha256']==sha(base)
result=[]
for operation in patch['operations']:
    if operation[0]=='copy':
        start,length=operation[1:];assert 0<=start<=len(base) and 0<=length<=len(base)-start;result.append(base[start:start+length])
    else:assert operation[0]=='insert';result.append(base64.b64decode(operation[1],validate=True))
apk=b''.join(result);assert sha(apk)==cfg['apk_sha256']==patch['signed_sha256']
with zipfile.ZipFile(io.BytesIO(apk)) as z:
    assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))
    for name,digest in cfg['routing'].items():assert sha(z.read('assets/'+name))==digest
    for name,digest in cfg['native'].items():assert sha(z.read(name))==digest
print('Signed APK reconstructed byte-for-byte; routing and all native hashes verified',flush=True)
base_source=request('https://github.com/SpecterLogic/Drkozol-source/releases/download/v1.5.1/DrKozol-source-1.5.1.zip',binary=True)
assert sha(base_source)=='507f4245cd0e2569cf70e9dbf43bbced1df4692a0c084f4a1fdd5a95c9f98419'
records=subprocess.check_output(['git','ls-files','--stage','-z']).decode().split('\0');tracked=set();out=io.BytesIO()
with zipfile.ZipFile(io.BytesIO(base_source)) as original,zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    def write(name,data,mode=0o100644):
        assert not any(p in ('.tools','.signing','.git') for p in Path(name).parts)
        assert b'forouzandeh' not in data.lower()
        info=zipfile.ZipInfo('Drkozol/'+name);info.compress_type=zipfile.ZIP_DEFLATED;info.create_system=3;info.external_attr=mode<<16;archive.writestr(info,data)
    for record in records:
        if not record:continue
        meta,name=record.split('\t',1);mode,_,_=meta.split();assert mode!='160000';tracked.add(name);write(name,Path(name).read_bytes(),int(mode,8))
    for entry in original.infolist():
        name=entry.filename.removeprefix('Drkozol/')
        if name in tracked or name=='BUILD-INPUTS.json':continue
        if name.startswith(('V2rayNG/app/libs/','V2rayNG/awg/src/main/jniLibs/','native-source/')):write(name,original.read(entry.filename),entry.external_attr>>16)
    metadata=json.loads(original.read('Drkozol/BUILD-INPUTS.json'));metadata['source_commit']=cfg['source_commit']
    for name,digest in cfg['routing'].items():metadata['native_input_sha256']['V2rayNG/app/src/main/assets/'+name]=digest
    write('BUILD-INPUTS.json',json.dumps(metadata,indent=2).encode())
source_data=out.getvalue();source_name='DrKozol-source-1.5.1-android-r2.zip'
source_asset=upload(source,source_name,source_data)
upload(source,'SHA256SUMS.txt',(sha(source_data)+'  '+source_name+'\n').encode('ascii'))
new_apk=upload(release,'DrKozol-1.5.1-android-r2.pending',apk)
checks={name:a['digest'].removeprefix('sha256:') for name,a in assets.items() if name.endswith(('.apk','.zip','.deb','.tar.gz'))}
checks['DrKozol-1.5.1.apk']=sha(apk);checksum=''.join(digest+'  '+name+'\n' for name,digest in sorted(checks.items())).encode('ascii')
new_checksum=upload(release,'SHA256SUMS.android-r2.pending',checksum)
# Publish corresponding source before changing the installation download.
for part in cfg['overlay']['parts']:request(src+'/releases/assets/'+str(part_assets[part['name']]['id']),'DELETE')
published_source=request(src+'/releases/'+str(source['id']),'PATCH',{'draft':False,'tag_name':'v1.5.1-android-r2','target_commitish':cfg['source_commit'],'make_latest':'false'})
current=request(app+'/releases/'+str(release['id']));assert next(a for a in current['assets'] if a['name']=='DrKozol-1.5.1.apk')['digest']==old['digest']
request(app+'/releases/assets/'+str(old['id']),'DELETE')
request(app+'/releases/assets/'+str(new_apk['id']),'PATCH',{'name':'DrKozol-1.5.1.apk'})
request(app+'/releases/assets/'+str(assets['SHA256SUMS.txt']['id']),'DELETE')
request(app+'/releases/assets/'+str(new_checksum['id']),'PATCH',{'name':'SHA256SUMS.txt'})
final=request(app+'/releases/'+str(release['id']));final_assets={a['name']:a for a in final['assets']}
assert final_assets['DrKozol-1.5.1.apk']['digest']=='sha256:'+cfg['apk_sha256']
assert final_assets['SHA256SUMS.txt']['digest']=='sha256:'+sha(checksum)
assert len(final_assets)==5 and sha(final['body'].encode())==cfg['body_sha256']
for name,digest in cfg['desktop'].items():assert final_assets[name]['digest']==digest
print(json.dumps({'published':final['html_url'],'apk_sha256':sha(apk),'source':published_source['html_url'],'source_sha256':sha(source_data),'desktop_assets_unchanged':True,'release_body_unchanged':True}),flush=True)
