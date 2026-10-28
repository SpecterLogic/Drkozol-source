"""Store clean validation packages in an owned unpublished source-repository draft."""
from pathlib import Path
import hashlib,io,json,os,sys,urllib.request,zipfile

def main():
    assert os.environ['GITHUB_REPOSITORY']=='SpecterLogic/Drkozol-source'
    repo='https://api.github.com/repos/'+os.environ['GITHUB_REPOSITORY']
    headers={'Authorization':'Bearer '+os.environ['GITHUB_TOKEN'],'Accept':'application/vnd.github+json','User-Agent':'DrKozol-validation'}
    def request(url,data=None):
        values=dict(headers)
        if data is not None:values['Content-Type']='application/octet-stream'
        with urllib.request.urlopen(urllib.request.Request(url,headers=values,data=data),timeout=300) as response:return json.load(response)
    release=request(repo+'/releases/'+os.environ['DRAFT_RELEASE_ID'])
    assert release['draft'] and release['tag_name'].startswith('v1.5.0-validation-')
    if sys.argv[1]=='windows':
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
            for path in sorted(Path('artifacts/windows').rglob('*')):
                if not path.is_file():continue
                relative=path.relative_to('artifacts/windows')
                assert not path.is_symlink() and not {'.signing','.git','guiConfigs','guiLogs','guiTemps','guiBackups','binConfigs'}.intersection(relative.parts)
                assert not path.name.startswith(('smoke-','private-'))
                data=path.read_bytes()
                assert b'c:\\users\\' not in data.lower()
                info=zipfile.ZipInfo('DrKozol/'+relative.as_posix());info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
                archive.writestr(info,data)
        assets={'DrKozol-1.5.0-windows-x64.zip':output.getvalue()}
    else:
        assert sys.argv[1]=='linux'
        assets={p.name:p.read_bytes() for p in Path('artifacts/packages').glob('DrKozol-1.5.0-linux-*')}
        assert set(assets)=={'DrKozol-1.5.0-linux-amd64.deb','DrKozol-1.5.0-linux-x64.tar.gz'}
    for name,data in assets.items():
        digest='sha256:'+hashlib.sha256(data).hexdigest()
        previous=next((a for a in release['assets'] if a['name']==name),None)
        if previous:assert previous['digest']==digest
        else:
            uploaded=request(release['upload_url'].split('{')[0]+'?name='+name,data)
            assert uploaded['digest']==digest and uploaded['size']==len(data)
        print('Verified unpublished package:',name,digest,flush=True)

if __name__=='__main__':main()
