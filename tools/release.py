"""
Publishes a release: bumps the version, builds the signed APK, commits, tags, pushes, and creates the
GitHub release the app's auto-updater reads.

    python tools/release.py 3.2.0 "What changed in this release"

Extra Gradle arguments can go in the GRADLE_ARGS environment variable
(e.g. "--project-cache-dir .gradle-b -PoutDir=build2" when another Gradle process holds app/build).
"""
import os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE_FILE = os.path.join(ROOT, "app", "build.gradle.kts")


def run(*cmd, **kw):
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True, **kw)


def main():
    if len(sys.argv) < 3 or not re.fullmatch(r"\d+\.\d+\.\d+", sys.argv[1]):
        sys.exit(__doc__)
    version, notes = sys.argv[1], sys.argv[2]
    major, minor, patch = map(int, version.split("."))
    code = major * 10000 + minor * 100 + patch
    if not os.path.exists(os.path.expanduser(os.path.join("~", ".android", "debug.keystore"))):
        sys.exit("~/.android/debug.keystore is missing: releases must be signed with it or installed apps can't update")
    if subprocess.run(["git", "rev-parse", "-q", "--verify", f"refs/tags/v{version}"], cwd=ROOT, capture_output=True).returncode == 0:
        sys.exit(f"v{version} is already tagged")

    src = open(GRADLE_FILE, encoding="utf-8").read()
    src = re.sub(r'\?: \d+\n', f"?: {code}\n", src, count=1)
    src = re.sub(r'\?: "[\d.]+"\n', f'?: "{version}"\n', src, count=1)
    open(GRADLE_FILE, "w", encoding="utf-8").write(src)

    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    run(gradlew, *os.environ.get("GRADLE_ARGS", "").split(), "assembleRelease", "--console=plain", "-q")
    out = re.search(r"-PoutDir=(\S+)", os.environ.get("GRADLE_ARGS", ""))
    apk = os.path.join(ROOT, "app", out.group(1) if out else "build", "outputs", "apk", "release", "app-release.apk")
    os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)
    asset = os.path.join(ROOT, "dist", f"StylusNotes-{version}.apk")
    shutil.copy(apk, asset)

    run("git", "add", "-A")
    if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode != 0:
        run("git", "commit", "-m", f"Release v{version}\n\n{notes}")
    run("git", "tag", f"v{version}")
    run("git", "push", "origin", "HEAD", f"v{version}")
    run("gh", "release", "create", f"v{version}", asset, "--title", f"Stylus Notes {version}", "--notes", notes)
    print(f"\nPublished v{version}. Installed apps will pick it up within 6 hours, or at once via Library > Check for updates.")


if __name__ == "__main__":
    main()
