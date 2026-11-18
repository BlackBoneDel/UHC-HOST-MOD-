#!/usr/bin/env python3
"""Boot the shaded plugin on an official Paper 26.3 build in an isolated CI server."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zipfile

BUILD_INDEX = "https://fill.papermc.io/v3/projects/paper/versions/26.3/builds"
USER_AGENT = "UhcCore-CI/1.0 (https://github.com/BlackBoneDel/UHC-HOST-MOD-)"


def request(url: str, method: str = "GET") -> Request:
    return Request(url, method=method, headers={"User-Agent": USER_AGENT})


def download_paper(destination: Path) -> int:
    with urlopen(request(BUILD_INDEX), timeout=40) as response:
        builds = json.load(response)
    stable = [build for build in builds if build.get("channel") == "STABLE" and build["id"] >= 200]
    if not stable:
        raise RuntimeError("No stable Paper 26.3 build >= 200 available")
    selected = max(stable, key=lambda entry: entry["id"])
    binary = selected["downloads"]["server:default"]
    checksum = binary["checksums"]["sha256"]
    print(f"Downloading official Paper 26.3 build {selected['id']} ({binary['size']} bytes)", flush=True)
    with urlopen(request(binary["url"]), timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = hashlib.sha256(destination.read_bytes()).hexdigest()
    if actual != checksum:
        raise RuntimeError(f"Paper jar SHA-256 mismatch: {actual} != {checksum}")
    return selected["id"]


def read_log(path: Path) -> str:
    return path.read_text(errors="replace") if path.exists() else ""


def wait_for_log(process: subprocess.Popen, path: Path, phrase: str, timeout: int) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        output = read_log(path)
        if phrase in output:
            return output
        if process.poll() is not None:
            raise RuntimeError(f"Paper exited with code {process.returncode} before '{phrase}'")
        time.sleep(0.5)
    raise RuntimeError(f"Paper did not emit '{phrase}' within {timeout}s")


def check_pack(artifact: Path) -> None:
    with zipfile.ZipFile(artifact) as plugin:
        pack = plugin.read("packs/uhccore-pack.zip")
    url = "http://127.0.0.1:8088/uhccore-pack.zip"
    with urlopen(request(url), timeout=10) as response:
        actual = response.read()
        if response.status != 200 or response.headers.get("Content-Type") != "application/zip":
            raise RuntimeError("Bundled pack HTTP response has incorrect status or content type")
        if response.headers.get("ETag") != '"' + hashlib.sha1(pack).hexdigest() + '"':
            raise RuntimeError("Bundled pack ETag does not match its SHA-1")
    if actual != pack:
        raise RuntimeError("Paper's resource-pack HTTP host did not serve the exact bundled ZIP")
    with urlopen(request(url, method="HEAD"), timeout=10) as response:
        if response.status != 200 or int(response.headers["Content-Length"]) != len(pack):
            raise RuntimeError("Bundled pack HEAD response has an invalid size")
    try:
        urlopen(request("http://127.0.0.1:8088/not-a-pack.zip"), timeout=10)
    except HTTPError as error:
        if error.code != 404:
            raise RuntimeError(f"Unexpected HTTP response for unknown pack: {error.code}") from error
    else:
        raise RuntimeError("Pack server exposed an unknown path")
    print(f"Pack HTTP verified: {len(pack)} bytes, SHA-1 {hashlib.sha1(pack).hexdigest()}", flush=True)


def main(artifact: Path) -> None:
    base = Path("build/paper-smoke").resolve()
    server = base / "server"
    (server / "plugins").mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifact, server / "plugins" / artifact.name)
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(
        "online-mode=false\nserver-ip=\nserver-port=25565\n"
        "level-name=smoke_world\nlevel-type=minecraft:flat\n"
        "allow-nether=false\nview-distance=2\nsimulation-distance=2\n"
        "max-players=2\nspawn-protection=0\nenable-rcon=false\n"
    )
    build = download_paper(base / "paper.jar")
    log_path = server / "server.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(["java", "-Xms512M", "-Xmx2G", "-jar", str(base / "paper.jar"), "nogui"],
                                   cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                   text=True)
        try:
            output = wait_for_log(process, log_path, "Done (", 240)
            if "Cannot initialize UhcCore" in output or "Error occurred while enabling UhcCore" in output:
                raise RuntimeError("UhcCore failed to initialize on Paper")
            if "[UhcCore] Enabling UhcCore" not in output:
                raise RuntimeError("Paper started, but UhcCore was not enabled")
            if "Using internal ranks.yml permissions" not in output:
                raise RuntimeError("UhcCore did not initialize its permissions/database")
            check_pack(artifact)
            process.stdin.write("uhc info\n")
            process.stdin.flush()
            wait_for_log(process, log_path, "Este comando requiere un jugador", 30)
            print(f"Paper 26.3 build {build}: UhcCore enabled, /uhc registered, SQLite and HTTP pack ready", flush=True)
        except Exception as error:
            tail = "\n".join(read_log(log_path).splitlines()[-100:])[-13000:]
            annotation = f"{type(error).__name__}: {error}\n{tail}"
            print("::error title=Paper 26.3 smoke test::" + annotation.replace("%", "%25")
                  .replace("\r", "%0D").replace("\n", "%0A"), flush=True)
            raise
        finally:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=60)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait(timeout=15)
    if process.returncode != 0:
        raise RuntimeError(f"Paper exited with code {process.returncode} after stop")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: smoke_paper.py path/to/uhccore-1.0.0.jar")
    main(Path(sys.argv[1]).resolve())
