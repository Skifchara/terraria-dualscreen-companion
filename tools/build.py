#!/usr/bin/env python3
"""Build the Terraduo stage-1 artifacts. Pure stdlib + the SDK installed under tools/android-sdk.

  python tools/build.py            -> build/libterraduo_bridge.so  and  build/terraduo-companion.apk (dev-signed)

The dev keystore (tools/dev.keystore, gitignored) is created on first run. It is a DEVELOPMENT key
(password "terraduo-dev") - never use it for anything you distribute.
This script does NOT touch Terraria's APK, PairIP or any licence check.
"""
import glob, os, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Where the Android SDK lives: $ANDROID_SDK_ROOT / $ANDROID_HOME, else tools/android-sdk (as set up by BUILD.md).
SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or os.path.join(ROOT, "tools", "android-sdk")
WIN = os.name == "nt"


def _latest(folder):
    names = sorted(os.listdir(folder)) if os.path.isdir(folder) else []
    return os.path.join(folder, names[-1]) if names else folder


BT = _latest(os.path.join(SDK, "build-tools"))
ANDROID_JAR = os.path.join(SDK, "platforms", "android-33", "android.jar")
NDK = os.path.join(SDK, "ndk", "27.2.12479018", "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin")
BUILD = os.path.join(ROOT, "build")
KEYSTORE = os.path.join(ROOT, "tools", "dev.keystore")
STORE_PASS = "terraduo-dev"
JAVA_HOME = os.environ.get("JAVA_HOME")
JDK_BIN = os.path.join(JAVA_HOME, "bin") if JAVA_HOME else ""


def exe(name):
    """Tool name with the platform's extension: aapt2 -> aapt2.exe on Windows."""
    return name + (".exe" if WIN else "")


def script(name):
    """SDK shell wrappers: d8 -> d8.bat on Windows."""
    return name + (".bat" if WIN else "")


def jdk(name):
    path = os.path.join(JDK_BIN, exe(name)) if JDK_BIN else exe(name)   # falls back to PATH
    return path


def run(cmd, **kw):
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True, **kw)


def build_bridge():
    if not os.path.exists(os.path.join(ROOT, "src", "bridge", "bridge.cpp")):
        return   # the native bridge experiment is not part of this repository
    out = os.path.join(BUILD, "libterraduo_bridge.so")
    run([os.path.join(NDK, "aarch64-linux-android21-clang++.cmd"), "-std=c++17", "-O2", "-Wall", "-Wextra",
         "-fvisibility=hidden", "-fno-exceptions", "-fno-rtti", "-shared", "-static-libstdc++",
         "-o", out, os.path.join(ROOT, "src", "bridge", "bridge.cpp"), "-llog", "-ldl"])


def ensure_keystore():
    if os.path.exists(KEYSTORE):
        return
    run([jdk("keytool"), "-genkeypair", "-keystore", KEYSTORE, "-storepass", STORE_PASS,
         "-keypass", STORE_PASS, "-alias", "terraduo-dev", "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10000", "-dname", "CN=Terraduo Development"])


def build_companion():
    src = os.path.join(ROOT, "src", "companion")
    work = os.path.join(BUILD, "companion")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(os.path.join(work, "classes"))
    base = os.path.join(work, "base.apk")
    compiled = os.path.join(work, "res.zip")
    run([os.path.join(BT, exe("aapt2")), "compile", "--dir", os.path.join(src, "res"), "-o", compiled])
    run([os.path.join(BT, exe("aapt2")), "link", "-o", base, "-I", ANDROID_JAR,
         "--manifest", os.path.join(src, "AndroidManifest.xml"), "--min-sdk-version", "26",
         "--target-sdk-version", "33", compiled])
    java = glob.glob(os.path.join(src, "java", "**", "*.java"), recursive=True)
    run([jdk("javac"), "--release", "8", "-Xlint:-options", "-g:none", "-cp", ANDROID_JAR,
         "-d", os.path.join(work, "classes")] + java)
    # pass a jar: class paths containing '$' get mangled by the d8.bat wrapper
    jar = os.path.join(work, "classes.jar")
    cdir = os.path.join(work, "classes")
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as zj:
        for f in glob.glob(os.path.join(cdir, "**", "*.class"), recursive=True):
            zj.write(f, os.path.relpath(f, cdir).replace(os.sep, "/"))
    run([os.path.join(BT, script("d8")), "--lib", ANDROID_JAR, "--release", "--min-api", "26",
         "--output", work, jar])
    unsigned = os.path.join(work, "unsigned.apk")
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(os.path.join(work, "classes.dex"), "classes.dex")
    aligned = os.path.join(work, "aligned.apk")
    run([os.path.join(BT, exe("zipalign")), "-f", "-p", "4", unsigned, aligned])
    ensure_keystore()
    final = os.path.join(BUILD, "terraduo-companion.apk")
    run([os.path.join(BT, script("apksigner")), "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + STORE_PASS,
         "--key-pass", "pass:" + STORE_PASS, "--out", final, aligned])
    run([os.path.join(BT, script("apksigner")), "verify", "--print-certs", final])


if __name__ == "__main__":
    os.makedirs(BUILD, exist_ok=True)
    build_bridge()
    build_companion()
    print("OK ->", BUILD)
