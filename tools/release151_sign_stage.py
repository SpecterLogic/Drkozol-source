import base64,gzip,hashlib,json,os,urllib.request
def sha(data):return hashlib.sha256(data).hexdigest()
token=os.environ['GH_TOKEN'];source='https://api.github.com/repos/SpecterLogic/Drkozol-source';app='https://api.github.com/repos/SpecterLogic/Drkozol'
class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        next=super().redirect_request(req,fp,code,msg,headers,newurl)
        if next is not None:next.remove_header('Authorization')
        return next
opener=urllib.request.build_opener(Redirect())
def request(url,binary=False,data=None):
    h={'Authorization':'Bearer '+token,'Accept':'application/octet-stream' if binary else 'application/vnd.github+json','User-Agent':'DrKozol-signature-transfer'}
    if data is not None:h['Content-Type']='application/octet-stream'
    with opener.open(urllib.request.Request(url,headers=h,data=data),timeout=300) as r:
        b=r.read();return b if binary else json.loads(b)
assert request('https://api.github.com/user')['login']=='SpecterLogic'
s=request(source+'/releases/'+os.environ['SOURCE_RELEASE_ID']);assert s['draft'] and s['tag_name']=='v1.5.1'
assets={a['name']:a for a in s['assets']}
base=request(source+'/releases/assets/'+str(assets['DrKozol-1.5.1-unsigned.apk']['id']),True)
patch=request(source+'/releases/assets/'+str(assets['DrKozol-1.5.1.signature.gz']['id']),True)
assert 'sha256:'+sha(base)==assets['DrKozol-1.5.1-unsigned.apk']['digest']
assert 'sha256:'+sha(patch)==assets['DrKozol-1.5.1.signature.gz']['digest']
p=json.loads(gzip.decompress(patch));assert p['format']==1 and sha(base)==p['base_sha256']
chunks=[]
for op in p['operations']:
    if op[0]=='copy':
        start,length=op[1:];assert 0<=start<=len(base) and 0<=length<=len(base)-start;chunks.append(base[start:start+length])
    else:assert op[0]=='insert';chunks.append(base64.b64decode(op[1],validate=True))
payload=b''.join(chunks);assert sha(payload)==p['signed_sha256']
r=request(app+'/releases/'+os.environ['APP_RELEASE_ID']);assert r['draft'] and r['tag_name']=='v1.5.1'
name='DrKozol-1.5.1.apk';old=next((a for a in r['assets'] if a['name']==name),None)
if old:assert old['digest']=='sha256:'+sha(payload)
else:
    a=request(r['upload_url'].split('{')[0]+'?name='+name,data=payload)
    assert a['digest']=='sha256:'+sha(payload) and a['size']==len(payload)
print('Byte-identical locally signed APK staged and remote SHA-256 verified:',sha(payload))
