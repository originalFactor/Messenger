"""Launch Messenger's terminal client."""

import argparse
import asyncio
import os
import signal
import subprocess
import sys
from pathlib import Path

from .app import Messenger, display
from .chat import Chat, model_for
from .core import open_core


def executable_name() -> str:
    return "messenger-core-server.exe" if os.name == "nt" else "messenger-core-server"


def default_core() -> Path:
    directory = Path(__file__).resolve().parents[1]
    bundled = directory / "bin" / executable_name()
    if bundled.is_file():
        return bundled
    return directory.parent / "core" / "rust" / "target" / "debug" / executable_name()


async def run(executable: Path, database: Path, workspace: Path) -> None:
    database.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    async with open_core(executable, database) as (api, core):
        meta, agent = await asyncio.gather(api.json("/meta"), api.json("/agents/default"))
        app = Messenger(Chat(api, agent, await model_for(api, agent)), meta["serverVersion"], workspace=workspace)
        watch = asyncio.create_task(core.wait())
        terminal = asyncio.create_task(app.run_async())
        loop = asyncio.get_running_loop()
        previous_termination = signal.getsignal(signal.SIGTERM)
        handles_termination = False
        try:
            loop.add_signal_handler(signal.SIGTERM, app.exit)
            handles_termination = True
        except (NotImplementedError, RuntimeError, ValueError):
            # Windows event loops don't support Unix signal handlers.
            pass
        try:
            done, _ = await asyncio.wait((terminal, watch), return_when=asyncio.FIRST_COMPLETED)
            if watch in done and not terminal.done():
                app.exit()
                await terminal
                raise RuntimeError(watch.result())
            await terminal
        finally:
            await app.stop_work()
            if not terminal.done():
                app.exit()
            watch.cancel()
            await asyncio.gather(terminal, watch, return_exceptions=True)
            if handles_termination:
                loop.remove_signal_handler(signal.SIGTERM)
                signal.signal(signal.SIGTERM, previous_termination)


def main() -> int:
    parser = argparse.ArgumentParser(prog="messenger", description="Messenger terminal chat client")
    parser.add_argument(
        "--db",
        type=Path,
        default=Path.home() / ".messenger" / "tui.db",
        help="SQLite profile (default: ~/.messenger/tui.db)",
    )
    parser.add_argument("--core", type=Path, default=default_core(), help="Core executable")
    parser.add_argument("--workspace", type=Path, help="startup directory (default: current directory)")
    parser.add_argument("-V", "--version", action="store_true", help="print the Messenger version")
    args = parser.parse_args()
    executable = args.core.expanduser().resolve()
    try:
        if args.version:
            version = subprocess.check_output([executable, "--version"], text=True).strip()
            print(f"Messenger {version}")
        else:
            if not sys.stdin.isatty() or not sys.stdout.isatty():
                raise RuntimeError("Messenger TUI needs an interactive terminal.")
            if os.environ.get("TERM") == "dumb":
                raise RuntimeError(
                    "Messenger TUI needs a cursor-addressable terminal. Use --help for command-line options."
                )
            workspace = (args.workspace or Path.cwd()).expanduser().resolve(strict=True)
            if not workspace.is_dir():
                raise ValueError("The startup workspace must be a directory.")
            asyncio.run(run(executable, args.db.expanduser().resolve(), workspace))
    except KeyboardInterrupt:
        return 130
    except Exception as error:
        print(f"Messenger: {display(str(error) or type(error).__name__)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
