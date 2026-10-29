"""Transfer frozen Linux installers and exact corresponding source only."""
import base64,gzip,hashlib,io,json,os,urllib.request,zipfile
def digest(data):
    return hashlib.sha256(data).hexdigest()

def restore(base, patch):
    if patch['format'] != 1 or digest(base) != patch['base_sha256']:
        raise ValueError('CI APK does not match the signed source')
    chunks = []
    for operation in patch['operations']:
        if operation[0] == 'copy':
            start, length = operation[1:]
            if not isinstance(start, int) or not isinstance(length, int) or start < 0 or length < 0 or start + length > len(base):
                raise ValueError('Invalid copy range')
            chunks.append(base[start:start + length])
        elif operation[0] == 'insert':
            chunks.append(base64.b64decode(operation[1], validate=True))
        else:
            raise ValueError('Invalid overlay operation')
    result = b''.join(chunks)
    if digest(result) != patch['signed_sha256']:
        raise ValueError('Reconstructed signature/package mismatch')
    return result

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = super().redirect_request(req, fp, code, msg, headers, newurl)
        if target is not None:
            target.remove_header('Authorization')
        return target

def main():
 private=os.environ['GITHUB_TOKEN'];public=os.environ['PUBLIC_RELEASE_TOKEN']
 private_root='https://api.github.com/repos/'+os.environ['GITHUB_REPOSITORY']
 public_root='https://api.github.com/repos/SpecterLogic/'
 opener=urllib.request.build_opener(SafeRedirect())
 def request(url,token=None,data=None,binary=False,method=None):
  headers={'User-Agent':'DrKozol-release-transfer','Accept':'application/octet-stream' if binary else 'application/vnd.github+json'}
  if token:headers['Authorization']='Bearer '+token
  if data is not None:headers['Content-Type']='application/json' if method=='PATCH' else 'application/octet-stream'
  with opener.open(urllib.request.Request(url,headers=headers,data=data,method=method),timeout=300) as r:b=r.read()
  return b if binary else (json.loads(b) if b else None)
 assert request('https://api.github.com/user',public)['login']=='SpecterLogic'
 cfg=json.loads(request(private_root+'/releases/assets/'+os.environ['CONFIG_ASSET_ID'],private,binary=True))
 assert cfg['version']=='1.5.0'
 for runid in cfg['validation_runs']:
  assert request(private_root+'/actions/runs/'+str(runid),private)['conclusion']=='success'
 assert request(public_root+'Drkozol-source/git/commits/'+cfg['source_commit'],public)['sha']==cfg['source_commit']
 def preserved():
  release=request(public_root+'Drkozol/releases/427130879',public)
  assert not release['draft'] and release['tag_name']=='v1.5.0'
  for name,digest in cfg['preserved'].items():
   a=next(a for a in release['assets'] if a['name']==name)
   assert a['digest']==digest and a['state']=='uploaded'
 preserved()
 for item in cfg['items']:
  assert item['repository'] in ('Drkozol','Drkozol-source')
  endpoint=public_root+item['repository'];r=request(endpoint+'/releases/'+str(item['release_id']),public)
  assert r['author']['login']=='SpecterLogic'
  assert r['tag_name']==('v1.5.0-desktop' if item['repository']=='Drkozol-source' else 'v1.5.0')
  if 'patch_id' in item:
   assert item['base_url']=='https://github.com/SpecterLogic/Drkozol-source/releases/download/v1.5.0/DrKozol-source-1.5.0.zip'
   base=request(item['base_url'],binary=True)
   patch=json.loads(gzip.decompress(request(private_root+'/releases/assets/'+str(item['patch_id']),private,binary=True)))
   payload=restore(base,patch)
   with zipfile.ZipFile(io.BytesIO(payload)) as z:
    assert json.loads(z.read('Drkozol/BUILD-INPUTS.json'))['source_commit']==cfg['source_commit']
  else:
   assert item['name'] in ('DrKozol-1.5.0-linux-amd64.deb','DrKozol-1.5.0-linux-x64.tar.gz','DrKozol-1.5.0-windows-x64.zip')
   payload=request(private_root+'/releases/assets/'+str(item['base_id']),private,binary=True)
  digest='sha256:'+hashlib.sha256(payload).hexdigest();assert digest==item['digest']
  old=next((a for a in r['assets'] if a['name']==item['name']),None)
  if old and old['digest']==digest:
   assert old['state']=='uploaded'
  else:
   if old:
    assert old['digest']==cfg['replace_allowed'][item['name']] and old['uploader']['login']=='SpecterLogic'
   temporary=item['name']+'.verified-transfer'
   pending=next((a for a in r['assets'] if a['name']==temporary),None)
   if pending:assert pending['digest']==digest and pending['state']=='uploaded'
   else:
    pending=request(r['upload_url'].split('{')[0]+'?name='+temporary,public,data=payload)
    assert pending['digest']==digest and pending['size']==len(payload)
   if old:request(endpoint+'/releases/assets/'+str(old['id']),public,method='DELETE')
   renamed=request(endpoint+'/releases/assets/'+str(pending['id']),public,data=json.dumps({'name':item['name']}).encode(),method='PATCH')
   assert renamed['name']==item['name'] and renamed['digest']==digest
  print('Verified exact release asset:',item['name'],digest,flush=True)
 preserved()

if __name__=='__main__':main()
