"""Scan publishable source and decompressed APK entries without printing secret values."""
from __future__ import annotations

import argparse
import ipaddress
import os
from pathlib import Path
import re
import socket
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOKEN = re.compile(rb"\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{24,}|gh[pousr]_[A-Za-z0-9]{24,})\b")
FORBIDDEN = re.compile(r"(?:^|/)(?:\.env(?:\..*)?|local\.properties|[^/]+\.(?:keystore|jks|db))$")


def known_values(env_file: Path | None) -> list[tuple[str, bytes]]:
    values = dict(os.environ)
    if env_file:
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            key, sep, value = line.strip().partition("=")
            if sep and not key.startswith("#"):
                values[key.strip()] = value.strip().strip("\"'")
    needles = []
    for home in {str(Path.home()), Path.home().as_posix()}:
        needles.append(("local user path", home.encode()))
    for key, value in values.items():
        if re.search(r"(?:API_KEY|AUTH_TOKEN|ACCESS_TOKEN|SECRET)$", key) and len(value) >= 12:
            needles.append(("known credential", value.encode()))
    # Runtime network addresses may be inherited on the device, never baked into the APK.
    for address in socket.gethostbyname_ex(socket.gethostname())[2]:
        parsed = ipaddress.ip_address(address)
        if parsed.is_private and not parsed.is_loopback:
            needles.append(("local network address", address.encode()))
    return needles


def scan(data: bytes, needles: list[tuple[str, bytes]]) -> list[str]:
    found = [label for label, value in needles if value in data]
    if TOKEN.search(data):
        found.append("credential-shaped token")
    return sorted(set(found))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args()
    needles = known_values(args.env_file)
    # Fail closed if the detector itself stops recognizing plausible credentials.
    assert TOKEN.search(("sk-" + "X" * 32).encode())
    failures = []
    paths = subprocess.check_output(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=ROOT
    ).decode().split("\0")
    count = 0
    for relative in filter(None, paths):
        path = ROOT / relative
        if not path.is_file():
            continue
        if FORBIDDEN.search(relative) and not relative.endswith(".env.example"):
            failures.append((relative, ["private configuration or data file"]))
        if path.stat().st_size > 8 * 1024 * 1024:
            continue
        # The scanner's own matching literals are not application data.
        if path.resolve() == Path(__file__).resolve():
            continue
        reasons = scan(path.read_bytes(), needles)
        count += 1
        if reasons:
            failures.append((relative, reasons))
    with zipfile.ZipFile(args.apk) as archive:
        for item in archive.infolist():
            reasons = scan(archive.read(item), needles)
            if reasons:
                failures.append(("APK:" + item.filename, reasons))
    if failures:
        for path, reasons in failures:
            print(f"FAIL {path}: {', '.join(reasons)}")
        return 1
    print(f"Privacy check passed: {count} source files and all decompressed APK entries; no matching credentials, local IPs or personal paths.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
