"""Platform execution for Core's built-in workspace tools.

Like Desktop, commands run with the current user's permissions. Inspection
mode is model guidance for the terminal, not an operating-system sandbox.
"""

import asyncio
import json
import os
import signal
import tempfile
from pathlib import Path

MAX_BYTES = 4 * 1024 * 1024


async def command(root: Path, *args: str, shell: bool = False) -> tuple[str, int]:
    # A temporary file bounds memory even when a command produces huge output.
    with tempfile.TemporaryFile() as output:
        launch = asyncio.create_subprocess_shell if shell else asyncio.create_subprocess_exec
        process = await launch(*args, cwd=root, stdin=asyncio.subprocess.DEVNULL,
                               stdout=output, stderr=output, start_new_session=os.name != "nt")
        try:
            await asyncio.wait_for(process.wait(), 60)
        finally:
            if os.name != "nt":
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            elif process.returncode is None:
                process.kill()
            await process.wait()
        output.seek(0, 2)
        length = output.tell()
        output.seek(max(0, length - 40_000))
        text = output.read().decode("utf-8", errors="replace")
        return text, process.returncode


def file_tool(root: Path, writable: bool, name: str, args: dict) -> str:
    def path() -> Path:
        value = args["path"]
        if not isinstance(value, str) or not value:
            raise ValueError("path must be a nonempty string")
        return (root / value).resolve()

    def read(target: Path) -> str:
        with target.open("rb") as source:
            data = source.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError("File exceeds the 4 MiB limit.")
        return data.decode("utf-8")

    if name == "read":
        start = max(1, int(args.get("startLine", 1)))
        count = min(2000, max(1, int(args.get("maxLines", 200))))
        return "\n".join(f"{i}: {line}" for i, line in enumerate(read(path()).splitlines(), 1)
                         if start <= i < start + count) or "(no lines)"
    if name not in ("edit", "create"):
        raise ValueError(f"Unknown tool: {name}")
    if not writable:
        raise ValueError("File editing is disabled for this conversation.")
    target = path()
    if name == "create":
        content = args["content"]
        if not isinstance(content, str):
            raise ValueError("content must be a string")
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("w" if args.get("overwrite", False) else "x", encoding="utf-8") as output:
            output.write(content)
        return f"Created {target}"
    old, new = args["oldText"], args["newText"]
    if not isinstance(old, str) or not old or not isinstance(new, str):
        raise ValueError("oldText must be nonempty and newText must be a string")
    content = read(target)
    matches = content.count(old)
    if not matches or (matches != 1 and not args.get("replaceAll", False)):
        raise ValueError(f"Expected an exact match; found {matches}. Use replaceAll for multiple matches.")
    target.write_text(content.replace(old, new), encoding="utf-8")
    return f"Replaced {matches} occurrence(s) in {target}"


async def execute_tool(project: dict | None, writable: bool, name: str, arguments: str) -> dict:
    try:
        if not project or project.get("owner", {}).get("kind") != "local":
            raise ValueError("A local trusted project is required for workspace tools.")
        root = Path(project["workspace"]).resolve(strict=True)
        if not root.is_dir():
            raise ValueError("Workspace is not a directory.")
        args = json.loads(arguments)
        if not isinstance(args, dict):
            raise ValueError("Tool arguments must be an object.")
        failed = False
        if name == "terminal":
            value = args["command"]
            if not isinstance(value, str) or not value.strip():
                raise ValueError("command must be a nonempty string")
            output, code = await command(root, value, shell=True)
            output += f"\n(exit code: {code})"
            failed = code != 0
        elif name in ("glob", "grep"):
            pattern = args["pattern"]
            if not isinstance(pattern, str) or not pattern:
                raise ValueError("pattern must be a nonempty string")
            limit = min(1000, max(1, int(args.get("maxResults", 200))))
            rg = ["rg", "--no-config", "--hidden", "--no-ignore", "-g", "!.git"]
            if name == "glob":
                rg += ["--files", "-g", pattern]
            else:
                rg += ["--no-heading", "--line-number", "--color", "never"]
                if not args.get("caseSensitive", True):
                    rg += ["--ignore-case"]
                if args.get("fixedString", False):
                    rg += ["--fixed-strings"]
                if args.get("fileGlob"):
                    rg += ["-g", args["fileGlob"]]
                rg += ["--", pattern, args.get("path") or "."]
            output, code = await command(root, *rg)
            failed = code not in (0, 1)
            if not failed:
                lines = output.splitlines()
                output = "\n".join(lines[:limit]) or "No matches."
                if len(lines) > limit:
                    output += "\n(results truncated)"
        else:
            # File operations are bounded; keep writes on the event loop so
            # cancellation cannot leave an untracked background file mutation.
            output = file_tool(root, writable, name, args)
        if len(output) > 10_000:
            output = "(output truncated, showing last 10000 characters)\n" + output[-10_000:]
        return {"output": output or "(no output)", "isError": failed}
    except TimeoutError:
        return {"output": "Tool timed out after 60 seconds.", "isError": True}
    except Exception as error:
        return {"output": f"Tool failed: {error}", "isError": True}
