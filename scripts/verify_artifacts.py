#!/usr/bin/env python3
"""Validate the shaded release and both embedded resource packs without a game server."""

import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"Invalid plugin artifact: {message}")


def check_pack(content: bytes, legacy: bool) -> None:
    with zipfile.ZipFile(io.BytesIO(content)) as pack:
        require(pack.testzip() is None, "corrupted resource pack")
        names = set(pack.namelist())
        require("pack.mcmeta" in names, "missing pack.mcmeta at ZIP root")
        require("assets/uhccore/font/negative_space.json" in names, "missing HUD glyphs")
        meta = json.loads(pack.read("pack.mcmeta"))["pack"]
        glyphs = json.loads(pack.read("assets/uhccore/font/negative_space.json"))
        advances = glyphs["providers"][0]["advances"]
        require(advances.get("\uf801") == -1 and advances.get("\uf82f") == 24,
                "negative-space font advances do not match the HUD")
        if legacy:
            require(meta["min_format"] == [7, 0] and meta["max_format"] == [97, 0],
                    "legacy format range changed unexpectedly")
            require("assets/uhccore/items/golden_head.json" not in names,
                    "modern item format leaked into the legacy pack")
        else:
            require(meta["min_format"] == [97, 1] and meta["max_format"] == [97, 1],
                    "native 26.3 pack format must be 97.1")
            require("assets/uhccore/items/golden_head.json" in names,
                    "native golden head item definition missing")
            require("assets/uhccore/models/item/golden_head.json" in names,
                    "native golden head model missing")
            texture = pack.read("assets/uhccore/textures/item/golden_head.png")
            require(texture[:8] == b"\x89PNG\r\n\x1a\n" and
                    struct.unpack(">II", texture[16:24]) == (16, 16),
                    "native golden head texture missing or invalid")
        print(f"  {'legacy' if legacy else 'native'} pack: {len(content)} bytes, SHA-1 {hashlib.sha1(content).hexdigest()}")


def main(path: Path) -> None:
    require(path.is_file(), f"missing JAR: {path}")
    with zipfile.ZipFile(path) as jar:
        require(jar.testzip() is None, "corrupted shaded JAR")
        names = set(jar.namelist())
        for item in ("paper-plugin.yml", "com/blackbone/uhccore/UhcCorePlugin.class",
                     "com/blackbone/uhccore/libs/sqlite/SQLiteDriver.class",
                     "org/sqlite/JDBC.class",
                     "com/blackbone/uhccore/libs/adventure/nbt/BinaryTag.class",
                     "com/blackbone/uhccore/libs/adventure/examination/Examinable.class",
                     "META-INF/LICENSE", "META-INF/NOTICE",
                     "META-INF/licenses/sqlite-jdbc-LICENSE.txt",
                     "META-INF/licenses/Adventure-NBT-LICENSE.txt",
                     "META-INF/licenses/Kyori-Examination-LICENSE.txt",
                     "packs/uhccore-pack.zip", "packs/uhccore-legacy-pack.zip"):
            require(item in names, f"missing bundled resource: {item}")
        metadata = jar.read("paper-plugin.yml").decode("utf-8")
        require("api-version: '26.3'" in metadata and "${version}" not in metadata,
                "Paper metadata was not expanded for 26.3")
        for package in ("com/blackbone/uhccore/libs/packetevents/",
                        "com/blackbone/uhccore/libs/fastboard/",
                        "com/blackbone/uhccore/libs/hikari/",
                        "com/blackbone/uhccore/libs/mysql/"):
            require(any(name.startswith(package) and name.endswith(".class") for name in names),
                    f"missing shaded dependency: {package}")
        for package in ("com/github/retrooper/packetevents/", "io/github/retrooper/packetevents/",
                        "fr/mrmicky/fastboard/", "com/zaxxer/hikari/", "com/mysql/cj/",
                        "net/kyori/adventure/nbt/", "net/kyori/examination/", "org/bukkit/"):
            require(not any(name.startswith(package) and name.endswith(".class") for name in names),
                    f"dependency not relocated or bundled server API: {package}")
        require(any(name.startswith("org/sqlite/native/") and name.endswith((".so", ".dll", ".dylib"))
                    for name in names), "SQLite JNI binaries are absent")
        print(f"Shaded plugin: {path} ({path.stat().st_size} bytes, SHA-256 {hashlib.sha256(path.read_bytes()).hexdigest()})")
        check_pack(jar.read("packs/uhccore-pack.zip"), False)
        check_pack(jar.read("packs/uhccore-legacy-pack.zip"), True)


if __name__ == "__main__":
    require(len(sys.argv) == 2, "usage: verify_artifacts.py path/to/plugin.jar")
    main(Path(sys.argv[1]))
