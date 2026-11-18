#!/usr/bin/env python3
"""Prepare (and, only when asked, publish) a release so versions can never drift apart.

  python tools/release.py 1.0.8                 bump versions, build, assemble build/release/  (nothing is published)
  python tools/release.py 1.0.8 --publish       ... then create the GitHub pre-release and upload the files
  python tools/release.py --check               verify that manifest, TL Pro pack and CHANGELOG agree (used by CI)

What "bump versions" means:
  * AndroidManifest.xml: versionName = <version>, versionCode + 1
  * src/tlpro/Settings.json: the pack "version" goes up by one when anything under src/tlpro changed since the last tag
    (TL Pro compares it to decide whether an import is an update), and the .tl file is named after it
  * CHANGELOG.md must already have a "## <version>" section; it becomes the release notes

Needs ANDROID_SDK_ROOT (build-tools, platforms/android-33) and JAVA_HOME like tools/build.py.
Publishing uses the GitHub credential already stored by git (git credential fill); no token is read from or written to disk here.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "src", "companion", "AndroidManifest.xml")
SETTINGS = os.path.join(ROOT, "src", "tlpro", "Settings.json")
CHANGELOG = os.path.join(ROOT, "CHANGELOG.md")
OUT = os.path.join(ROOT, "build", "release")
REPO = "Skifchara/terraria-dualscreen-companion"


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def git(*args, check=True):
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    if check and result.returncode != 0:
        sys.exit("git %s failed: %s" % (" ".join(args), result.stderr.strip()))
    return result.stdout.strip()


def manifest_version():
    text = read(MANIFEST)
    return (re.search(r'versionName="([^"]+)"', text).group(1), int(re.search(r'versionCode="(\d+)"', text).group(1)))


def pack_version():
    return int(json.loads(read(SETTINGS))["version"])


def changelog_has(version):
    return re.search(r"^## %s\b" % re.escape(version), read(CHANGELOG), re.M) is not None


def changelog_notes(version):
    text = read(CHANGELOG)
    match = re.search(r"^## %s\b.*?\n(.*?)(?=^## |\Z)" % re.escape(version), text, re.M | re.S)
    return match.group(1).strip() if match else ""


def check():
    name, code = manifest_version()
    problems = []
    if not changelog_has(name):
        problems.append("CHANGELOG.md has no '## %s' section for the manifest version" % name)
    last_tag = git("describe", "--tags", "--abbrev=0", check=False)
    if last_tag:
        changed = git("diff", "--name-only", last_tag, "--", "src/tlpro")
        old = git("show", "%s:src/tlpro/Settings.json" % last_tag, check=False)
        if changed and old and json.loads(old)["version"] == pack_version():
            problems.append("src/tlpro changed since %s but the TL Pro pack version (%d) did not" % (last_tag, pack_version()))
    for problem in problems:
        print("FAIL:", problem)
    if not problems:
        print("OK: manifest %s (code %d), pack version %d, changelog section present" % (name, code, pack_version()))
    return 0 if not problems else 1


def bump(version):
    name, code = manifest_version()
    text = read(MANIFEST)
    if name != version:
        text = re.sub(r'versionName="[^"]+"', 'versionName="%s"' % version, text)
        text = re.sub(r'versionCode="\d+"', 'versionCode="%d"' % (code + 1), text)
        write(MANIFEST, text)
    last_tag = git("describe", "--tags", "--abbrev=0", check=False)
    if last_tag:
        changed = git("diff", "--name-only", last_tag, "--", "src/tlpro")
        old = git("show", "%s:src/tlpro/Settings.json" % last_tag, check=False)
        if changed and old and json.loads(old)["version"] >= pack_version():
            settings = read(SETTINGS)
            settings = re.sub(r'("version"\s*:\s*)\d+', lambda m: m.group(1) + str(json.loads(old)["version"] + 1), settings)
            write(SETTINGS, settings)


def build():
    python = sys.executable
    for script in ("build.py", "build_tlpro.py"):
        subprocess.check_call([python, os.path.join(ROOT, "tools", script)], cwd=ROOT)


def assemble(version):
    os.makedirs(OUT, exist_ok=True)
    for name in os.listdir(OUT):
        os.remove(os.path.join(OUT, name))
    apk = os.path.join(OUT, "Terraduo-Companion-%s.apk" % version)
    mod = os.path.join(OUT, "Terraduo-Companion-mod-v%d.tl" % pack_version())
    with open(os.path.join(ROOT, "build", "terraduo-companion.apk"), "rb") as src, open(apk, "wb") as dst:
        dst.write(src.read())
    with open(os.path.join(ROOT, "build", "terraduo-hotbar.tl"), "rb") as src, open(mod, "wb") as dst:
        dst.write(src.read())
    subprocess.check_call([sys.executable, os.path.join(ROOT, "tools", "make_installer_zip.py"), version, apk, mod, OUT], cwd=ROOT)
    files = sorted(os.listdir(OUT))
    lines = []
    for name in files:
        with open(os.path.join(OUT, name), "rb") as handle:
            lines.append("%s *%s" % (hashlib.sha256(handle.read()).hexdigest(), name))
    write(os.path.join(OUT, "SHA256SUMS.txt"), "\n".join(lines) + "\n")
    return sorted(os.listdir(OUT))


def token():
    request = "protocol=https\nhost=github.com\nusername=%s\n\n" % REPO.split("/")[0]
    result = subprocess.run(["git", "credential", "fill"], input=request, capture_output=True, text=True, cwd=ROOT)
    for line in result.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    sys.exit("No stored GitHub credential for git; sign in with git first.")


def api(method, url, body=None, data=None, content_type="application/json", secret=None):
    request = urllib.request.Request(url, method=method, data=data if data is not None else (json.dumps(body).encode() if body else None))
    request.add_header("Authorization", "Bearer " + secret)
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("Content-Type", content_type)
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode())


def publish(version, notes_file):
    if git("status", "--porcelain"):
        sys.exit("The working tree has uncommitted changes: commit them first so the tag matches the files.")
    git("push", "origin", "main")
    notes = read(notes_file) if notes_file else changelog_notes(version)
    secret = token()
    release = api("POST", "https://api.github.com/repos/%s/releases" % REPO, secret=secret, body={
        "tag_name": "v" + version, "target_commitish": "main", "name": "%s (pre-release)" % version, "body": notes,
        "draft": False, "prerelease": True})
    print("created", release["html_url"])
    for name in sorted(os.listdir(OUT)):
        with open(os.path.join(OUT, name), "rb") as handle:
            api("POST", "https://uploads.github.com/repos/%s/releases/%d/assets?name=%s" % (REPO, release["id"], name),
                data=handle.read(), content_type="application/octet-stream", secret=secret)
        print("uploaded", name)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version", nargs="?")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--notes", help="markdown file to use instead of the CHANGELOG section")
    args = parser.parse_args()
    if args.check:
        sys.exit(check())
    if not args.version:
        parser.error("a version is required")
    if not changelog_has(args.version):
        sys.exit("Add a '## %s' section to CHANGELOG.md first (it becomes the release notes)." % args.version)
    bump(args.version)
    build()
    files = assemble(args.version)
    print("\nAssembled in %s:\n  " % OUT + "\n  ".join(files))
    if git("status", "--porcelain"):
        print("\nVersions were bumped: commit the changes (manifest, Settings.json) before publishing.")
    if args.publish:
        publish(args.version, args.notes)
    else:
        print("\nNothing was published. Re-run with --publish to create the GitHub release.")


if __name__ == "__main__":
    main()
