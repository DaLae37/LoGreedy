"""Validate the exact distribution; no GitHub credentials or network calls."""
import argparse
import hashlib
from pathlib import Path
import re
import struct
import zipfile

FILES = ("LoGreedy-jdk8.jar", "LoGreedy-jdk21.jar", "LoGreedy.properties")
ASSETS = FILES + ("SHA256SUMS",)
ROOT = Path(__file__).resolve().parents[1]


def ReadVersion(root=ROOT):
    versions = re.findall(r"^version\s*=\s*(\S+)\s*$", (root / "gradle.properties").read_text(), re.M)
    if len(versions) != 1 or not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", versions[0]):
        raise ValueError("Expected one version=MAJOR.MINOR.PATCH in gradle.properties")
    return versions[0]


def Digest(data):
    return hashlib.sha256(data).hexdigest()


def CheckJar(data, version, modern):
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or len(names) > 10000:
            raise ValueError("Duplicate/excess JAR entries")
        if sum(entry.file_size for entry in archive.infolist()) > 67108864:
            raise ValueError("Oversized expanded JAR")
        main = "com.dalae37.logreedy.runtime." + ("jdk21" if modern else "jdk8") + ".Main"
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "")
        if "Main-Class: " + main + "\r\n" not in manifest or "Implementation-Version: " + version + "\r\n" not in manifest:
            raise ValueError("JAR manifest main/version mismatch")
        if main.replace(".", "/") + ".class" not in names:
            raise ValueError("Missing executable Main class")
        count = 0
        for name in names:
            if not name.endswith(".class"):
                continue
            if not name.startswith("com/dalae37/logreedy/"):
                raise ValueError("Unreviewed external runtime dependency: " + name)
            with archive.open(name) as stream:
                magic, minor, major = struct.unpack(">IHH", stream.read(8))
            expected = 65 if modern and name.startswith("com/dalae37/logreedy/runtime/") else 52
            if magic != 0xCAFEBABE or major != expected:
                raise ValueError("Incompatible class version: " + name)
            count += 1
        if count < 2 or archive.read("logreedy-version.properties").decode().strip() != "version=" + version:
            raise ValueError("Missing Core or wrong version resource")


def ReadFiles(directory):
    result = {}
    for name in FILES:
        path = directory / name
        if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 67108864:
            raise ValueError("Missing/unsafe/empty/oversized distribution file: " + name)
        result[name] = path.read_bytes()
    return result


def Validate(directory, version, prepare=False):
    files = ReadFiles(directory)
    CheckJar(files[FILES[0]], version, False)
    CheckJar(files[FILES[1]], version, True)
    checksums = "".join(Digest(files[name]) + "  " + name + "\n" for name in FILES).encode("ascii")
    target = directory / "SHA256SUMS"
    if target.is_symlink():
        raise ValueError("Unsafe checksum path")
    if prepare:
        target.write_bytes(checksums)
    elif not target.is_file() or target.read_bytes() != checksums:
        raise ValueError("SHA256SUMS mismatch or missing; refusing release")
    files["SHA256SUMS"] = checksums
    return files


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--dist", type=Path, default=ROOT / "build/distributions/all")
    args = parser.parse_args()
    Validate(args.dist, ReadVersion(), args.prepare)
    print("Verified executable JARs, class versions, runtime dependency boundary and SHA256SUMS")
