import subprocess, urllib.parse, urllib.request, os, time

REPO = "liuy13-h/Arcane-Brigade"
SHA = "3a8339933332b7f0252270c07d5ec09fd5a44dc4"
BASE = f"https://raw.githubusercontent.com/{REPO}/{SHA}/"
ROOT = "D:/Code_Projects/arcane-brigade"
DL = os.path.join(ROOT, "_dl")

with open(os.path.join(DL, "theirs.txt"), encoding="utf-8") as f:
    paths = [l.strip() for l in f if l.strip()]

def fetch(url, out, tries=5):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "git-inject/1.0"})
            data = urllib.request.urlopen(req, timeout=120).read()
            with open(out, "wb") as w:
                w.write(data)
            return data
        except Exception as e:
            last = e
            time.sleep(1.5)
    raise last

ok = 0
for p in paths:
    url = BASE + urllib.parse.quote(p)
    out = os.path.join(DL, p.replace("/", "_").replace(" ", "_"))
    try:
        data = fetch(url, out)
    except Exception as e:
        print(f"FAIL  {p}: {e}")
        continue
    r = subprocess.run(["git", "-c", "safe.directory=*", "hash-object", "-w", out],
                       cwd=ROOT, capture_output=True, text=True)
    if r.returncode == 0:
        ok += 1
        print(f"OK    {p} -> {r.stdout.strip()} ({len(data)} bytes)")
    else:
        print(f"HASHFAIL {p}: {r.stderr.strip()}")

print(f"\nINJECTED {ok}/{len(paths)} blobs")
