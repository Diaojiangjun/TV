#!/usr/bin/env python3
"""Collects runtime APKs, builds a multi-source catalog and publishes it.

Examples:
  py -3 scripts/publish_runtime.py --collect --staging Release/staging \
    --output Release/module --aapt2 D:\\Android\\Sdk\\build-tools\\37.0.0\\aapt2.exe \
    --url-base https://github.com/Diaojiangjun/media/releases/download/module \
    --url-base https://ghfast.top/https://github.com/Diaojiangjun/media/releases/download/module \
    --url-base https://gh-proxy.com/https://github.com/Diaojiangjun/media/releases/download/module \
    --github-repo Diaojiangjun/media --netdisk Release/netdisk --record catalog/catalog.json
"""

import argparse
import shlex
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runtime_catalog

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("python", "nodejs", "quickjs", "thunder", "bdj", "mpv")
ABIS = (("arm64", "arm64_v8a"), ("arm32", "armeabi_v7a"))


def run(command, cwd=ROOT):
    print("+ " + " ".join(str(item) for item in command))
    subprocess.run(command, cwd=cwd, check=True)


def collect(staging):
    staging.mkdir(parents=True, exist_ok=True)
    missing = []
    for module in MODULES:
        for flavor, abi in ABIS:
            source = ROOT / "runtime" / "build" / module / "outputs" / "apk" / flavor / "release" / f"runtime-{module}-{abi}.apk"
            if not source.is_file():
                missing.append(source)
                continue
            shutil.copy2(source, staging / source.name)
    if missing:
        raise SystemExit("Missing module APKs:\n" + "\n".join(str(path) for path in missing))


def ensure_release(repo, tag):
    result = subprocess.run(["gh", "release", "view", tag, "--repo", repo], capture_output=True, text=True)
    if result.returncode == 0:
        return
    run(["gh", "release", "create", tag, "--repo", repo, "--title", tag, "--notes", "Runtime modules"])


def upload_github(repo, tag, files):
    ensure_release(repo, tag)
    run(["gh", "release", "upload", tag, *[str(file) for file in files], "--clobber", "--repo", repo])


def upload_mirror(template, files):
    for file in files:
        arguments = [part.format(file=file, name=file.name) for part in shlex.split(template)]
        run(arguments)


def create_netdisk(apk_files, catalog, netdisk):
    netdisk.mkdir(parents=True, exist_ok=True)
    for directory, suffix in (("arm64-v8a", "-arm64-v8a.apk"), ("armeabi-v7a", "-armeabi-v7a.apk")):
        selected = [apk for apk in apk_files if apk.name.endswith(suffix)]
        target = netdisk / directory
        target.mkdir(parents=True, exist_ok=True)
        for apk in selected:
            shutil.copy2(apk, target / apk.name)
        archive = netdisk / f"runtime-modules-{directory}.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_STORED) as output:
            for apk in selected:
                output.write(apk, apk.name)
    if catalog.is_file():
        shutil.copy2(catalog, netdisk / catalog.name)


def main():
    parser = argparse.ArgumentParser(description="Publish runtime modules to one or more sources")
    parser.add_argument("--input", type=Path, help="Directory with finalized runtime-<module>-<abi>.apk files")
    parser.add_argument("--collect", action="store_true", help="Collect APKs from runtime/build outputs first")
    parser.add_argument("--staging", type=Path, default=ROOT / "Release" / "staging", help="Staging directory used by --collect")
    parser.add_argument("--output", type=Path, default=ROOT / "Release" / "module", help="Catalog and versioned APK output")
    parser.add_argument("--aapt2", default="aapt2", help="Android SDK aapt2 executable")
    parser.add_argument("--url-base", action="append", required=True, help="Primary URL base; repeat for mirrors")
    parser.add_argument("--base-catalog", type=Path, help="Existing catalog to preserve unchanged modules")
    parser.add_argument("--github-repo", help="Upload to this GitHub repository (requires gh auth)")
    parser.add_argument("--release-tag", default="module", help="GitHub release tag")
    parser.add_argument("--mirror-command", help="Command template executed per file, e.g. 'ossutil cp {file} oss://bucket/module/{name}'")
    parser.add_argument("--netdisk", type=Path, help="Also build an ABI-sorted manual install directory")
    parser.add_argument("--record", type=Path, help="Copy the verified catalog to this tracked snapshot path")
    args = parser.parse_args()

    if args.collect:
        collect(args.staging)
        input_dir = args.staging
    elif args.input:
        input_dir = args.input
    else:
        parser.error("either --input or --collect is required")

    args.output.mkdir(parents=True, exist_ok=True)
    catalog = runtime_catalog.publish(input_dir, args.output, args.aapt2, args.url_base, args.base_catalog)
    runtime_catalog.read_catalog(args.output / "catalog.json")

    catalog_file = args.output / "catalog.json"
    if args.record:
        args.record.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(catalog_file, args.record)
        runtime_catalog.read_catalog(args.record)
        print(f"Recorded {args.record}")
    names = sorted({Path(artifact["url"]).name for module in catalog["modules"] for artifact in module["artifacts"]})
    apk_files = [args.output / name for name in names]
    missing = [file for file in apk_files if not file.is_file()]
    if missing:
        raise SystemExit("Catalog references missing files:\n" + "\n".join(str(file) for file in missing))
    files = apk_files + [catalog_file]
    if args.netdisk:
        create_netdisk(apk_files, catalog_file, args.netdisk)
    if args.github_repo:
        upload_github(args.github_repo, args.release_tag, files)
    if args.mirror_command:
        upload_mirror(args.mirror_command, files)

    print(f"Published {len(catalog['modules'])} modules to {len(args.url_base)} source(s)")
    for module in catalog["modules"]:
        print(f"- {module['id']} {module['versionName']} ({module['versionCode']})")
    for index, base in enumerate(args.url_base):
        label = "primary" if index == 0 else f"mirror {index}"
        print(f"  {label}: {base.rstrip('/')}/catalog.json")


if __name__ == "__main__":
    main()
