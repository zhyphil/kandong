#!/usr/bin/env python3
"""Explicit operator-only preparation/provisioning. Never deploy or call a provider.

No credentials on argv/stdout. `prepare --endpoint HTTPS_ORIGIN` uses ONLY this
project's private DeepL helper. --device selects a recorded test phone only.
"""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / ".local/translation/cloud"
SERVER = "server-secrets.json"
PHONE = "phone-config.json"
DEVICE_ID = "nova9-nam-lx9"  # Stable across rotations: never reset its quota identity.
SERIAL = "2AS0221B09001601"
TARGETS = {
    "nova9": {"id": DEVICE_ID, "serial": SERIAL, "model": "NAM-LX9", "phone": PHONE},
    "lio": {"id": "huawei-lio-an00", "serial": "2KE0220109017133", "model": "LIO-AN00", "phone": "phone-config-lio.json"},
}
PACKAGE = "com.kandong.compat.dev"
ADB = Path("/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb")
AUTHORITY_NAME = "deepl-api-free-pilot-v1"  # Documentation metadata, not client-selected.
LIFETIME_MS = 90 * 86400000
ORIGIN = re.compile(r"https://kandong-translation-pilot\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.workers\.dev(?::443)?\Z")


def endpoint(value):
    if not isinstance(value, str) or len(value) > 160 or not ORIGIN.fullmatch(value):
        raise ValueError("Only the dedicated HTTPS workers.dev origin is supported")
    return value.removesuffix(":443")


def private_directory(directory=DIRECTORY):
    for path in (ROOT / ".local", ROOT / ".local/translation", directory):
        if path.is_symlink():
            raise ValueError("Private directory must not be a symlink")
        path.mkdir(mode=0o700, exist_ok=True)
        info = path.stat()
        if info.st_uid != os.getuid() or not stat.S_ISDIR(info.st_mode):
            raise ValueError("Invalid private directory")
        path.chmod(0o700)


def read_private(path):
    # Paths supplied here are fixed operator artifacts, never arbitrary credential paths.
    for parent in path.parents:
        if parent == ROOT:
            break
        if parent.is_symlink():
            raise ValueError("Invalid private directory")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or info.st_nlink != 1 or info.st_size > 16384):
            raise ValueError("Private artifact is not secure")
        with os.fdopen(descriptor, "r", closefd=False) as stream:
            return json.load(stream)
    finally:
        os.close(descriptor)


def write_private(path, value):
    temp = path.with_name(path.name + "." + secrets.token_hex(8))
    descriptor = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


def validate_phone(config, now=None):
    now = int(time.time() * 1000) if now is None else now
    if not isinstance(config, dict) or set(config) != {"origin", "token", "expiresAt"}:
        raise ValueError("Invalid phone configuration")
    endpoint(config["origin"])
    if not isinstance(config["token"], str) or not re.fullmatch(r"[a-f0-9]{64}", config["token"]):
        raise ValueError("Invalid device credential")
    expiry = config["expiresAt"]
    if type(expiry) is not int or not now < expiry <= now + LIFETIME_MS:
        raise ValueError("Device credential expired or invalid; explicit rotation required")
    return config


def target(device):
    if device not in TARGETS:
        raise ValueError("Unknown recorded test phone")
    return TARGETS[device]


def server_devices(server):
    devices = json.loads(server["DEVICE_CREDENTIALS"])
    if not isinstance(devices, list) or len(devices) > 16:
        raise ValueError("Invalid device configuration")
    ids, hashes = set(), set()
    for item in devices:
        if (not isinstance(item, dict) or set(item) != {"id", "sha256", "expiresAt"}
                or not isinstance(item["id"], str) or not re.fullmatch(r"[a-z0-9_-]{1,32}", item["id"])
                or item["id"] == "global" or item["id"] in ids
                or not isinstance(item["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", item["sha256"])
                or item["sha256"] in hashes or type(item["expiresAt"]) is not int
                or not 0 < item["expiresAt"] <= int(time.time() * 1000) + LIFETIME_MS):
            raise ValueError("Invalid device configuration")
        ids.add(item["id"])
        hashes.add(item["sha256"])
    return devices


def make_bundle(key, origin, now=None, *, device_id=DEVICE_ID):
    """Pure preparation except randomness; used by offline tests with synthetic input."""
    now = int(time.time() * 1000) if now is None else now
    if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9:_-]{16,256}", key) or not key.endswith(":fx"):
        raise ValueError("An API Free key is required")
    token = secrets.token_hex(32)
    phone = {"origin": endpoint(origin), "token": token, "expiresAt": now + LIFETIME_MS}
    device = {"id": device_id, "sha256": hashlib.sha256(token.encode("ascii")).hexdigest(), "expiresAt": phone["expiresAt"]}
    return {"DEEPL_API_KEY": key, "DEVICE_CREDENTIALS": json.dumps([device], separators=(",", ":"))}, phone


def prepare(origin, rotate=False, device="nova9"):
    selected = target(device)
    origin = endpoint(origin)
    private_directory()
    server_path, phone_path = DIRECTORY / SERVER, DIRECTORY / selected["phone"]
    server = read_private(server_path) if server_path.exists() else None
    devices = server_devices(server) if server is not None else []
    previous = next((d for d in devices if d["id"] == selected["id"]), None)
    if previous is not None and not rotate:
        phone = validate_phone(read_private(phone_path))
        if (phone["origin"] != origin or previous["expiresAt"] != phone["expiresAt"]
                or not hmac.compare_digest(previous["sha256"], hashlib.sha256(phone["token"].encode()).hexdigest())):
            raise ValueError("Existing bundle differs; repair or rotate explicitly")
        print("Existing private bundle preserved; no credential rotation or deployment.")
        return
    if phone_path.exists() and not rotate:
        raise ValueError("Partial bundle exists; explicit rotation required")
    if previous is None and len(devices) >= 16:
        raise ValueError("Device configuration is full")
    if server is None:
        if any((DIRECTORY / t["phone"]).exists() for t in TARGETS.values() if t != selected):
            raise ValueError("Missing shared server bundle; preserve the other phone")
        # Access the provider helper only for initial, explicitly requested preparation.
        from deepl_credentials import load_key
        key = load_key()
    else:
        key = server["DEEPL_API_KEY"]
    created, phone = make_bundle(key, origin, device_id=selected["id"])
    entry = json.loads(created["DEVICE_CREDENTIALS"])[0]
    merged = [entry if d["id"] == selected["id"] else d for d in devices]
    if previous is None:
        merged.append(entry)
    server = {**(server or created), "DEVICE_CREDENTIALS": json.dumps(merged, separators=(",", ":"))}
    server_devices(server)  # Validate the combined service configuration before writing either file.
    write_private(phone_path, phone)
    write_private(server_path, server)
    print("Private 90-day pilot bundle prepared; server deployment and phone provisioning have not run.")


def revoke():
    private_directory()
    server = read_private(DIRECTORY / SERVER)
    server["DEVICE_CREDENTIALS"] = "[]"
    write_private(DIRECTORY / SERVER, server)
    for selected in TARGETS.values():
        path = DIRECTORY / selected["phone"]
        if path.exists():
            path.unlink()
    print("Private revocation bundle prepared. It must be applied to the Worker; no remote change made.")


def adb(*args, data=None, serial=SERIAL):
    result = subprocess.run([str(ADB), "-s", serial, *args], input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=15)
    if result.returncode:
        raise ValueError("Phone provisioning failed; no private output displayed")
    return result.stdout


def provision(device="nova9"):
    selected = target(device)
    phone = validate_phone(read_private(DIRECTORY / selected["phone"]))
    serial = selected["serial"]
    if (adb("get-state", serial=serial).strip() != b"device"
            or adb("shell", "getprop", "ro.product.model", serial=serial).strip().decode() != selected["model"]):
        raise ValueError("Expected recorded phone identity")
    body = json.dumps(phone, separators=(",", ":")).encode("ascii")
    # stdin, app-private no-backup directory, restrictive mode and same-directory atomic rename.
    # No adb reverse, install, permission changes, app launch or capture.
    adb("shell", "run-as", PACKAGE, "sh", "-c",
        "'umask 077; mkdir -p no_backup && cat > no_backup/cloud-config.tmp && chmod 600 no_backup/cloud-config.tmp && mv no_backup/cloud-config.tmp no_backup/cloud-config.pending'", data=body, serial=serial)
    size = adb("shell", "run-as", PACKAGE, "stat", "-c", "%s", "no_backup/cloud-config.pending", serial=serial).strip()
    if size != str(len(body)).encode():
        raise ValueError("Private staging verification failed")
    print("Phone private staging installed; app imports into Keystore on next translation start. No page sent.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--endpoint", required=True)
    prep.add_argument("--rotate", action="store_true")
    prep.add_argument("--device", choices=TARGETS, default="nova9")
    sub.add_parser("revoke")
    provision_parser = sub.add_parser("provision")
    provision_parser.add_argument("--device", choices=TARGETS, default="nova9")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.endpoint, args.rotate, args.device)
        elif args.command == "revoke":
            revoke()
        else:
            provision(args.device)
    except Exception:
        # Never print exceptions: provider helpers, OS or subprocess messages may contain secrets.
        raise SystemExit("Operation failed; private artifacts were not displayed. Check the runbook and local permissions.") from None


if __name__ == "__main__":
    main()
