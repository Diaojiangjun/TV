#!/usr/bin/env python3
"""Record and verify the private media3 AARs shipped in app/libs.

The player libraries are built from the media-fork checkout, which is not part of
this repository. This tool pins what was actually built so a downloaded AAR set
can be checked before it is compiled into the APK.

Modes:
  --record     Read the fork checkout, hash its build outputs and write the lock.
  --verify     Compare an AAR directory against the lock (default target: app/libs).
  --build      Run the release build for every library, then record.
  --audit      Report which native inputs are packaged, registered or orphaned.
  --compat-id  Print the MPV compatibility id implied by the AARs.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import BadZipFile, ZipFile

LOCK_SCHEMA = 1
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCK = ROOT / "player-libs.lock.json"
DEFAULT_PATCH = ROOT / "player-libs.patch"

# library directory in the fork -> AAR file name
LIBRARIES = {
    "common": "lib-common",
    "container": "lib-container",
    "database": "lib-database",
    "datasource": "lib-datasource",
    "datasource_okhttp": "lib-datasource-okhttp",
    "decoder": "lib-decoder",
    "decoder_ffmpeg": "lib-decoder-ffmpeg",
    "effect": "lib-effect",
    "exoplayer": "lib-exoplayer",
    "exoplayer_hls": "lib-exoplayer-hls",
    "extractor": "lib-extractor",
    "mpvplayer": "lib-mpvplayer",
    "session": "lib-session",
    "ui": "lib-ui",
    "ui_danmaku": "lib-ui-danmaku",
}

PROVENANCE = "META-INF/androidx.media3.decoder.ffmpeg/native-dependencies.properties"
ABIS = ("arm64-v8a", "armeabi-v7a")
# Mirrors AbiApkPackaging.MPV_LIBRARIES plus the shared runtime.
MPV_NATIVES = ("libmpv.so", "libplayer.so", "libc++_shared.so")


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def run(command, cwd):
    return subprocess.run(
        command, cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def parse_properties(text):
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def read_provenance(archive):
    """Java resources land in classes.jar for a library AAR, so look there first."""
    if PROVENANCE in archive.namelist():
        data = archive.read(PROVENANCE)
    elif "classes.jar" in archive.namelist():
        with ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            if PROVENANCE not in classes.namelist():
                return None
            data = classes.read(PROVENANCE)
    else:
        return None
    return {
        "sha256": sha256_bytes(data),
        "properties": parse_properties(data.decode("utf-8", "replace")),
    }


def read_aar(path):
    with ZipFile(path) as archive:
        natives = {}
        for name in sorted(archive.namelist()):
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "jni" and parts[2].endswith(".so"):
                natives.setdefault(parts[1], {})[parts[2]] = sha256_bytes(
                    archive.read(name)
                )
        return {
            "size": path.stat().st_size,
            "sha256": sha256_file(path),
            "natives": dict(sorted(natives.items())),
            "provenance": read_provenance(archive),
        }


def git(fork, *args):
    return run(["git", *args], fork)


def worktree_patch(fork):
    """Diff of the files the build actually reads, so a dirty tree stays reproducible."""
    patch = run(["git", "diff", "HEAD", "--binary", "--", "libraries"], fork)
    untracked = [
        line
        for line in run(
            ["git", "ls-files", "--others", "--exclude-standard", "--", "libraries"],
            fork,
        ).splitlines()
        if line.strip()
    ]
    return patch, untracked


def gradle_version(fork):
    text = (Path(fork) / "gradle/wrapper/gradle-wrapper.properties").read_text(
        encoding="utf-8"
    )
    match = re.search(r"gradle-([0-9][^-]*)-(?:bin|all)\.zip", text)
    return match.group(1) if match else None


def toolchain(native_properties):
    properties = native_properties or {}
    values = {
        "ndk": properties.get("android.ndk.version"),
        "ffmpeg": {
            "repository": properties.get("ffmpeg.repository"),
            "commit": properties.get("ffmpeg.commit"),
            "version": properties.get("ffmpeg.version"),
            "libavcodec": properties.get("ffmpeg.libavcodec.version"),
        },
    }
    for key, prefix in (
        ("mpv-android", "mpv-android."),
        ("libplacebo", "libplacebo."),
        ("dav1d", "dav1d."),
    ):
        entry = {
            name[len(prefix) :]: value
            for name, value in properties.items()
            if name.startswith(prefix)
        }
        if entry:
            values[key] = entry
    return values


def collect_provenance(fork, native_properties):
    return {
        "source": {
            "repository": git(fork, "remote", "get-url", "origin"),
            "commit": git(fork, "rev-parse", "HEAD"),
            "branch": git(fork, "rev-parse", "--abbrev-ref", "HEAD"),
            "dirty": bool(git(fork, "status", "--porcelain", "--", "libraries")),
            "gradle": gradle_version(fork),
        },
        "toolchain": toolchain(native_properties),
    }


def record(fork, lock_path, patch_path):
    fork = Path(fork).resolve()
    if not (fork / "libraries").is_dir():
        raise SystemExit(f"error: {fork} is not a media-fork checkout")
    artifacts = {}
    native_properties = None
    for directory, name in LIBRARIES.items():
        aar = (
            fork
            / "libraries"
            / directory
            / "buildout/outputs/aar"
            / f"{name}-release.aar"
        )
        if not aar.is_file():
            raise SystemExit(
                f"error: {aar} is missing; run --build first or assemble that module"
            )
        entry = read_aar(aar)
        if name == "lib-decoder-ffmpeg" and entry["provenance"]:
            native_properties = entry["provenance"]["properties"]
        artifacts[name] = entry

    patch, untracked = worktree_patch(fork)
    if patch.strip():
        # git apply rejects a diff that does not end with a newline.
        patch = patch.rstrip("\n") + "\n"
        patch_path.write_text(patch, encoding="utf-8", newline="\n")
        patch_sha = sha256_bytes(patch.encode("utf-8"))
    else:
        if patch_path.exists():
            patch_path.unlink()
        patch_sha = None

    provenance = collect_provenance(fork, native_properties)
    provenance["source"]["worktreePatch"] = (
        {"file": patch_path.name, "sha256": patch_sha} if patch_sha else None
    )
    provenance["source"]["untrackedFiles"] = [
        {"path": name, "size": (fork / name).stat().st_size} for name in untracked
    ]
    lock = {
        "schema": LOCK_SCHEMA,
        "provenance": provenance,
        "artifacts": dict(sorted(artifacts.items())),
    }
    lock_path.write_text(
        json.dumps(lock, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Recorded {len(artifacts)} AARs into {lock_path}")
    if patch_sha:
        print(
            f"Source tree was dirty; captured the build inputs as {patch_path.name} "
            f"({patch_sha[:12]})"
        )
    return lock


def native_diff(actual, expected):
    diffs = []
    for abi in sorted(set(actual["natives"]) | set(expected["natives"])):
        left, right = actual["natives"].get(abi, {}), expected["natives"].get(abi, {})
        for lib in sorted(set(left) | set(right)):
            if left.get(lib) != right.get(lib):
                diffs.append(f"{abi}/{lib}")
    return ", ".join(diffs)


def report(problems, success):
    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        raise SystemExit(1)
    if success:
        print(success)


def compatibility_id(directory):
    """Mirror MpvNativeLibraries.compatibilityId so the AARs and the catalog agree."""
    directory = Path(directory)
    entries = []
    with ZipFile(directory / "lib-mpvplayer-release.aar") as archive:
        for abi in ABIS:
            for name in MPV_NATIVES:
                entries.append(
                    f"{abi}/{name}:{sha256_bytes(archive.read(f'jni/{abi}/{name}'))}"
                )
    with ZipFile(directory / "lib-decoder-ffmpeg-release.aar") as archive:
        for abi in ABIS:
            entries.append(
                f"{abi}/libffmpeg.so:"
                f"{sha256_bytes(archive.read(f'jni/{abi}/libffmpeg.so'))}"
            )
    entries.sort()
    return "mpv-" + sha256_bytes("\n".join(entries).encode("utf-8"))[:16]


def verify(
    directory,
    lock_path,
    patch_path,
    strict_natives=True,
    check_artifacts=True,
    catalog=None,
):
    if not lock_path.is_file():
        raise SystemExit(f"error: {lock_path} is missing; run --record first")
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    if lock.get("schema") != LOCK_SCHEMA:
        raise SystemExit(f"error: unsupported lock schema {lock.get('schema')}")
    directory = Path(directory)
    problems = []

    patch = lock["provenance"]["source"].get("worktreePatch")
    if patch:
        if not patch_path.is_file():
            problems.append(f"{patch['file']} is missing but the lock requires it")
        elif sha256_file(patch_path) != patch["sha256"]:
            problems.append(f"{patch['file']} does not match the lock")

    if not check_artifacts:
        report(
            problems,
            f"Lock is consistent: {len(lock['artifacts'])} artifacts pinned",
        )
        return lock

    checked = 0
    for name, expected in lock["artifacts"].items():
        path = directory / f"{name}-release.aar"
        if not path.is_file():
            problems.append(f"{name}-release.aar is missing")
            continue
        actual = read_aar(path)
        if actual["size"] != expected["size"]:
            problems.append(f"{name}: size {actual['size']} != {expected['size']}")
            continue
        if actual["sha256"] != expected["sha256"]:
            problems.append(
                f"{name}: sha256 {actual['sha256'][:12]} != {expected['sha256'][:12]}"
            )
            continue
        if strict_natives and actual["natives"] != expected["natives"]:
            problems.append(f"{name}: natives differ at {native_diff(actual, expected)}")
            continue
        if expected.get("provenance") and actual["provenance"] != expected["provenance"]:
            problems.append(f"{name}: embedded provenance manifest does not match")
            continue
        checked += 1

    report(problems, "")

    derived = compatibility_id(directory)
    if catalog and Path(catalog).is_file():
        modules = json.loads(Path(catalog).read_text(encoding="utf-8"))["modules"]
        published = next(
            (module["compatibilityId"] for module in modules if module["id"] == "mpv"),
            None,
        )
        if published != derived:
            report(
                [
                    f"native libraries imply {derived} but {catalog} pins "
                    f"{published}; rebuild and republish the mpv runtime plugin"
                ],
                "",
            )
        print(f"MPV compatibility id matches {catalog}: {derived}")

    source = lock["provenance"]["source"]
    print(
        f"Verified {checked} AARs against commit {source['commit'][:7]}"
        + (" (with captured worktree patch)" if patch else "")
    )
    return lock


def referenced_native_dirs(fork, directory):
    """Native source dirs a module actually packages, from its setSrcDirs override."""
    gradle = Path(fork) / "libraries" / directory / "build.gradle.kts"
    if not gradle.is_file():
        return []
    text = gradle.read_text(encoding="utf-8")
    dirs = [
        path
        for block in re.findall(r"jniLibs\.setSrcDirs\((.*?)\)", text, re.S)
        for path in re.findall(r'"([^"]+)"', block)
    ]
    # Without an override AGP packages the conventional directory.
    return dirs or ["src/main/jniLibs"]


def audit(fork):
    """Show which native inputs are packaged, registered in the manifest, and orphaned."""
    fork = Path(fork).resolve()
    properties = (
        fork
        / "libraries/decoder_ffmpeg/src/main/resources/META-INF"
        / "androidx.media3.decoder.ffmpeg/native-dependencies.properties"
    )
    registered = {}
    if properties.is_file():
        text = properties.read_text(encoding="utf-8")
        for name, value in parse_properties(text).items():
            parts = name.split(".")
            if parts[0] == "sha256" and len(parts) == 4 and parts[2].startswith("lib"):
                registered[(parts[1], parts[2])] = value

    used = set()
    print("打包实际读取的原生目录：")
    for directory, name in LIBRARIES.items():
        for relative in referenced_native_dirs(fork, directory):
            root = fork / "libraries" / directory / relative
            if not root.is_dir():
                continue
            used.add(root.resolve())
            print(f"- {name}: {relative}")
            for abi_dir in sorted(p for p in root.iterdir() if p.is_dir()):
                for lib in sorted(abi_dir.glob("*.so")):
                    actual = sha256_file(lib)
                    expected = registered.get((abi_dir.name, lib.name))
                    if expected is None:
                        state = "未登记溯源"
                    elif expected == actual:
                        state = "与清单一致"
                    else:
                        state = "与清单不一致"
                    print(f"    {abi_dir.name}/{lib.name}  {actual[:12]}  {state}")

    print("\n磁盘上未被任何模块引用的原生库目录：")
    orphans = 0
    for directory in LIBRARIES:
        parent = fork / "libraries" / directory / "src/main"
        if not parent.is_dir():
            continue
        for candidate in sorted(parent.glob("jni*")):
            if not candidate.is_dir() or candidate.resolve() in used:
                continue
            libs = [
                lib
                for abi in candidate.iterdir()
                if abi.is_dir()
                for lib in abi.glob("*.so")
            ]
            if not libs:
                continue
            size = sum(lib.stat().st_size for lib in libs)
            print(
                f"- {candidate.relative_to(fork)}  {size / 1048576:.1f} MB  "
                f"{len(libs)} 个 .so  (未引用)"
            )
            orphans += 1
    if not orphans:
        print("- 无")
    if not properties.is_file():
        print("\n未找到 native-dependencies.properties，溯源清单缺失")


def build(fork, tasks, gradlew_args):
    fork = Path(fork).resolve()
    gradlew = fork / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if not gradlew.is_file():
        raise SystemExit(f"error: {gradlew} is missing")
    command = [str(gradlew), *tasks, *gradlew_args, "--console=plain"]
    print("+ " + " ".join(command))
    subprocess.run(command, cwd=fork, check=True)


def main():
    parser = argparse.ArgumentParser(
        description="Record and verify the private media3 AARs in app/libs."
    )
    parser.add_argument(
        "--fork", type=Path, default=Path("D:/tmp/media-fork"), help="media-fork checkout"
    )
    parser.add_argument(
        "--lock", type=Path, default=DEFAULT_LOCK, help="Lock file to read or write"
    )
    parser.add_argument(
        "--patch", type=Path, default=DEFAULT_PATCH, help="Captured worktree patch"
    )
    parser.add_argument("--dir", type=Path, default=Path("app/libs"), help="AAR directory")
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("catalog/catalog.json"),
        help="Catalog whose mpv compatibilityId must match",
    )
    parser.add_argument("--record", action="store_true", help="Write the lock")
    parser.add_argument("--verify", action="store_true", help="Verify against the lock")
    parser.add_argument("--build", action="store_true", help="Assemble before recording")
    parser.add_argument("--audit", action="store_true", help="Report native inputs")
    parser.add_argument("--compat-id", action="store_true", help="Print the compat id")
    parser.add_argument("--lock-only", action="store_true", help="Skip the AAR comparison")
    parser.add_argument("--skip-natives", action="store_true", help="Only compare AARs")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Extra Gradle arg")
    args = parser.parse_args()

    if not (args.record or args.verify or args.build or args.audit or args.compat_id):
        parser.error("choose --record, --verify, --build, --audit or --compat-id")

    try:
        if args.audit:
            audit(args.fork)
        if args.compat_id:
            print(compatibility_id(args.dir))
        if args.build:
            tasks = [
                f":lib-{name.replace('_', '-')}:assembleRelease" for name in LIBRARIES
            ]
            build(args.fork, tasks, args.gradle_arg)
            args.record = True
        if args.record:
            record(args.fork, args.lock, args.patch)
        if args.verify:
            verify(
                args.dir,
                args.lock,
                args.patch,
                not args.skip_natives,
                not args.lock_only,
                None if args.lock_only else args.catalog,
            )
    except (OSError, ValueError, KeyError, BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"error: {error}\n")


if __name__ == "__main__":
    main()
