from pathlib import Path
import os,json,sys,hashlib,zipfile,base64,urllib.request,io
ROOT=Path.cwd()
def sha(data):return hashlib.sha256(data).hexdigest()
def upload(repo,release_id,path):
    token=os.environ['GH_TOKEN'];base='https://api.github.com/repos/SpecterLogic/'+repo
    h={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','User-Agent':'DrKozol-release'}
    def req(url,data=None):
        headers=dict(h)
        if data is not None:headers['Content-Type']='application/octet-stream'
        with urllib.request.urlopen(urllib.request.Request(url,headers=headers,data=data),timeout=300) as r:return json.load(r)
    r=req(base+'/releases/'+str(release_id));assert r['draft'] and r['tag_name']=='v1.5.1'
    data=path.read_bytes();digest='sha256:'+sha(data)
    previous=next((a for a in r['assets'] if a['name']==path.name),None)
    if previous:assert previous['digest']==digest;print('Verified existing asset',path.name);return
    a=req(r['upload_url'].split('{')[0]+'?name='+path.name,data)
    assert a['digest']==digest and a['size']==len(data)
    print('Uploaded and verified:',path.name,a['digest'])
mode=sys.argv[1]
if mode=='prepare':
    manifest=json.loads(Path('manifest151.json').read_text())
    base=Path('artifacts/base-source.zip');assert sha(base.read_bytes())==manifest['base_sha256']
    with zipfile.ZipFile(base) as previous,zipfile.ZipFile('artifacts/DrKozol-source-1.5.1.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as out:
        for entry in manifest['entries']:
            name=entry['name'];assert name.startswith('Drkozol/') and '..' not in Path(name).parts
            if 'inline' in entry:data=base64.b64decode(entry['inline'])
            elif entry['checkout']:data=Path(name[len('Drkozol/'):]).read_bytes()
            else:data=previous.read(name)
            assert sha(data)==entry['sha256'],name
            info=zipfile.ZipInfo(name);info.compress_type=zipfile.ZIP_DEFLATED;info.create_system=3;info.external_attr=entry['attributes'];out.writestr(info,data)
            if name.startswith('Drkozol/V2rayNG/app/libs/'):
                p=Path(name[len('Drkozol/'):]);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    print('Source archive content manifest and native build inputs verified')
elif mode=='source':upload('Drkozol-source',os.environ['SOURCE_RELEASE_ID'],Path('artifacts/DrKozol-source-1.5.1.zip'))
elif mode=='unsigned':
    import shutil
    p=Path('artifacts/DrKozol-1.5.1-unsigned.apk');shutil.copy2('V2rayNG/app/build/outputs/apk/playstore/release/DrKozol_1.5.1_universal.apk',p)
    upload('Drkozol-source',os.environ['SOURCE_RELEASE_ID'],p)
elif mode=='windows-package':
    output=Path('artifacts/DrKozol-1.5.1-windows-x64.zip')
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(Path('artifacts/windows').rglob('*')):
            if not p.is_file():continue
            rel=p.relative_to('artifacts/windows');assert not {'.git','.signing','guiConfigs','guiLogs','guiTemps','guiBackups','binConfigs'}.intersection(rel.parts)
            data=p.read_bytes();assert b'forouzandeh' not in data.lower() and b'c:\\users\\' not in data.lower(),rel
            info=zipfile.ZipInfo('DrKozol/'+rel.as_posix());info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,data)
    print('Windows clean package privacy checks passed')
elif mode=='windows-upload':upload('Drkozol',os.environ['APP_RELEASE_ID'],Path('artifacts/DrKozol-1.5.1-windows-x64.zip'))
else:raise ValueError('Unknown stage')
