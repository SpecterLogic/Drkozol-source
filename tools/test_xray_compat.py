"""Exercise pinned native patches on the host before building Android libraries."""
import subprocess
from prepare_xray_compat import prepare

if __name__ == '__main__':
    _, source = prepare()
    subprocess.run(['go', 'test', '-race', '-ldflags=-checklinkname=0',
                    './infra/conf', './proxy/wireguard', '-run', 'TestDrKozol',
                    '-count=1', '-timeout=120s'], cwd=source, check=True)
