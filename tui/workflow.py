"""Local build, test, and release commands run inside the Poetry environment."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

TUI = Path(__file__).resolve().parent
ROOT = TUI.parent
CORE = "messenger-core-server"
CARGO = ["--locked", "--manifest-path", "core/rust/Cargo.toml", "-p", "messenger-http"]


def invoke(args, *, cwd=ROOT, capture=False, env=None):
    result = subprocess.run(
        args, cwd=cwd, env=env, check=True, encoding="utf-8", stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout.strip() if capture else None


def host() -> str:
    report = invoke(["rustc", "-vV"], capture=True)
    return next(line.removeprefix("host: ") for line in report.splitlines() if line.startswith("host: "))


def build(target: str, release: bool = False) -> Path:
    args = ["cargo", "build", *CARGO, "--target", target, "--bin", CORE, "--message-format=json-render-diagnostics"]
    if release:
        args.append("--release")
    for line in invoke(args, capture=True).splitlines():
        if not line.startswith("{"):
            continue
        event = json.loads(line)
        if event["reason"] == "compiler-artifact" and event["target"]["name"] == CORE and event["executable"]:
            return Path(event["executable"])
    raise RuntimeError("Cargo did not report the Core executable.")


def test(target: str, executable: Path) -> None:
    invoke(["cargo", "test", *CARGO, "--target", target])
    invoke([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=TUI,
           env={**os.environ, "MESSENGER_TUI_TEST_CORE": str(executable)})


def vendor_dependencies(stage: Path, bundle: Path) -> None:
    project = stage / "dependencies"
    project.mkdir()
    for file in ("pyproject.toml", "poetry.lock"):
        shutil.copy2(TUI / file, project / file)
    env = {key: value for key, value in os.environ.items() if key not in ("VIRTUAL_ENV", "CONDA_PREFIX", "POETRY_ACTIVE")}
    env.update(POETRY_VIRTUALENVS_IN_PROJECT="true", POETRY_VIRTUALENVS_CREATE="true",
               POETRY_VIRTUALENVS_OPTIONS_NO_PIP="true", POETRY_INSTALLER_PARALLEL="false")
    invoke(["poetry", "env", "use", sys.executable], cwd=project, env=env)
    python = invoke(["poetry", "env", "info", "--executable"], cwd=project, capture=True, env=env)
    if not python or not Path(python).parent.resolve().is_relative_to((project / ".venv").resolve()):
        raise RuntimeError("Poetry did not create an isolated release environment.")
    invoke(["poetry", "install", "--only", "main", "--no-root", "--no-interaction"], cwd=project, env=env)
    purelib = invoke([python, "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"], capture=True)
    shutil.copytree(purelib, bundle / "vendor", ignore=shutil.ignore_patterns("__pycache__"))


def bundle_release(target: str, executable: Path, output: Path) -> None:
    version = invoke([executable, "--version"], capture=True)
    if version == "0.0.0" or not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version):
        raise RuntimeError(f'Core reported an invalid release version "{version}". Check VERSION.')
    name = f"messenger-tui-{version}-{target}"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="messenger-tui-release-") as staging, tempfile.TemporaryDirectory(
        dir=output, prefix=".messenger-release-"
    ) as temporary_output:
        stage = Path(staging)
        bundle = stage / name
        shutil.copytree(TUI / "messenger_tui", bundle / "messenger_tui", ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copy2(ROOT / "LICENSE", bundle)
        (bundle / "bin").mkdir()
        shutil.copy2(executable, bundle / "bin")
        vendor_dependencies(stage, bundle)
        (bundle / "launch.py").write_text(
            "import sys\nfrom pathlib import Path\n"
            "sys.path[:0] = [str(Path(__file__).resolve().parent / 'vendor'), str(Path(__file__).resolve().parent)]\n"
            "from messenger_tui.__main__ import main\nraise SystemExit(main())\n", encoding="utf-8",
        )
        if os.name == "nt":
            (bundle / "messenger.cmd").write_bytes(b'@echo off\r\npython -I "%~dp0launch.py" %*\r\n')
        else:
            launcher = bundle / "messenger"
            launcher.write_bytes(b'#!/bin/sh\nhere=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1\n'
                                 b'exec python3 -I "$here/launch.py" "$@"\n')
            launcher.chmod(0o755)
        reported = invoke([sys.executable, "-I", bundle / "launch.py", "--version"], cwd=stage, capture=True)
        if reported != f"Messenger {version}":
            raise RuntimeError(f'Bundled TUI reported "{reported}".')

        archive = Path(temporary_output) / f"{name}.tar.gz"
        with tarfile.open(archive, "w:gz", dereference=True) as package:
            package.add(bundle, arcname=name)
        with archive.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        destination = output / archive.name
        archive.replace(destination)
        print(f"Release: {destination}\nSHA-256: {checksum}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("dev", help="build and launch, forwarding remaining arguments")
    commands.add_parser("build")
    commands.add_parser("test")
    commands.add_parser("release").add_argument("--out", type=Path, default=TUI / "dist")
    options = sys.argv[2:] if sys.argv[1:2] == ["dev"] else []
    args = parser.parse_args(["dev"] if options else None)
    try:
        target = host()
        executable = build(target, args.command == "release")
        if args.command == "dev":
            if options[:1] == ["--"]:
                options = options[1:]
            return subprocess.run([sys.executable, "-m", "messenger_tui", "--core", executable, *options], cwd=TUI).returncode
        if args.command == "build":
            print(f"Core: {executable}")
        else:
            test(target, executable)
            if args.command == "release":
                bundle_release(target, executable, args.out.resolve())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"TUI workflow: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
