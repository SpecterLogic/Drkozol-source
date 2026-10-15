"""Fetch the official pinned Linux x64 sing-box TUN bridge."""
from pathlib import Path
import hashlib, io, sys, tarfile, urllib.request

VERSION='1.14.2'
SHA256='a684484d7477d1437282ee411f4d131d0340aaad60a7868841ebd5d87dd8a0c6'
URL=f'https://github.com/SagerNet/sing-box/releases/download/v{VERSION}/sing-box-{VERSION}-linux-amd64.tar.gz'

def main():
    target=Path(sys.argv[1]).resolve()
    with urllib.request.urlopen(URL,timeout=90) as response: data=response.read(128*1024*1024+1)
    if hashlib.sha256(data).hexdigest()!=SHA256:raise SystemExit('Linux TUN archive digest mismatch')
    target.mkdir(parents=True,exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
        for member in archive.getmembers():
            if member.isfile():
                path=target/Path(member.name).name
                path.write_bytes(archive.extractfile(member).read())
                if path.name=='sing-box':path.chmod(0o755)
    if not (target/'sing-box').is_file():raise SystemExit('Linux TUN engine missing')
    print(f'sing-box {VERSION} Linux x64 verified')

if __name__=='__main__':main()
