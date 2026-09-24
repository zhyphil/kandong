"""KanDong-only local development credential storage. Never emits the key."""
import os
import re
import stat
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
KEY_DIR = ROOT / ".local" / "translation"
KEY_FILE = KEY_DIR / "deepl-api-key"


def validate_key(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9:_-]{16,256}", value):
        raise ValueError("Invalid key format (value hidden)")
    return value


def _private_dirs():
    for path in (ROOT / ".local", KEY_DIR):
        if path.is_symlink(): raise ValueError("Credential directory must not be a symlink")
        path.mkdir(mode=0o700, exist_ok=True)
        info = path.stat()
        if info.st_uid != os.getuid() or not stat.S_ISDIR(info.st_mode):
            raise ValueError("Invalid credential directory ownership")
        path.chmod(0o700)


def save_key(value):
    value = validate_key(value.strip())
    _private_dirs()
    descriptor = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
            raise ValueError("Invalid credential file")
        os.fchmod(descriptor, 0o600)
        os.ftruncate(descriptor, 0)
        with os.fdopen(descriptor, "w", closefd=False) as stream: stream.write(value + "\n")
    finally: os.close(descriptor)


def load_key():
    # Only this project's explicitly configured credential, never unrelated env/keychains.
    for path in (ROOT / ".local", KEY_DIR):
        if path.is_symlink(): raise ValueError("Invalid credential directory")
    descriptor = os.open(KEY_FILE, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_nlink != 1:
            raise ValueError("Credential file must be private")
        with os.fdopen(descriptor, "r", closefd=False) as stream: value = stream.read(258).strip()
        return validate_key(value)
    finally: os.close(descriptor)
