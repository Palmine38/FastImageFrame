import json
import os
import sys
import urllib.request
import uuid

PROJECT = "AihqhsgZ"
VERSION = sys.argv[1]
JAR_DIR = sys.argv[2]
CHANGELOG = sys.argv[3]
TOKEN_FILE = os.path.expanduser(os.environ.get("MODRINTH_TOKEN_FILE", "~/.modrinth_token"))
DRY = os.environ.get("DRY_RUN") is not None

TARGETS = [
    ("1.21.4", "1.21.4", ["1.21.4"]),
    ("1.21.5", "1.21.5", ["1.21.5"]),
    ("1.21.6-1.21.8", "1.21.6 to 1.21.8", ["1.21.6", "1.21.7", "1.21.8"]),
    ("1.21.9-1.21.10", "1.21.9 and 1.21.10", ["1.21.9", "1.21.10"]),
    ("1.21.11", "1.21.11", ["1.21.11"]),
    ("26.1", "26.1 to 26.1.2", ["26.1", "26.1.1", "26.1.2"]),
    ("26.2", "26.2", ["26.2"]),
    ("26.3", "26.3", ["26.3"]),
]


def token():
    with open(TOKEN_FILE, encoding="utf-8") as f:
        return f.read().strip()


def post(target, label, versions, auth):
    jar = os.path.join(JAR_DIR, f"FastImageFrames-{target}-fabric-{VERSION}.jar")
    data = {
        "name": f"Fast ImageFrames {VERSION} for Minecraft {label}",
        "version_number": f"{VERSION}+{target}",
        "changelog": CHANGELOG if target == TARGETS[-1][0] else "",
        "dependencies": [
            {"project_id": "P7dR8mSH", "dependency_type": "required"},
            {"project_id": "mOgUt4GM", "dependency_type": "optional"},
        ],
        "game_versions": versions,
        "version_type": "release",
        "loaders": ["fabric"],
        "featured": False,
        "project_id": PROJECT,
        "file_parts": ["file"],
        "primary_file": "file",
    }
    if DRY:
        print("DRY", os.path.basename(jar), os.path.getsize(jar), json.dumps({k: data[k] for k in ("name", "version_number", "game_versions")}), repr(data["changelog"]))
        return
    boundary = uuid.uuid4().hex
    body = bytearray()
    body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"data\"\r\nContent-Type: application/json\r\n\r\n".encode()
    body += json.dumps(data).encode() + b"\r\n"
    body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{os.path.basename(jar)}\"\r\nContent-Type: application/java-archive\r\n\r\n".encode()
    body += open(jar, "rb").read() + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    request = urllib.request.Request("https://api.modrinth.com/v2/version", data=bytes(body), method="POST", headers={
        "Authorization": auth,
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "User-Agent": "Palmine38/FastImageFrame release script",
    })
    try:
        with urllib.request.urlopen(request) as response:
            created = json.load(response)
            print("created", created["version_number"], created["id"])
    except urllib.error.HTTPError as e:
        print("FAILED", target, e.code, e.read().decode(errors="replace")[:500])
        sys.exit(1)


auth = None if DRY else token()
for target, label, versions in TARGETS:
    post(target, label, versions, auth)
