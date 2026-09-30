#!/usr/bin/env python3
"""Provision ONLY the temporary relay token to the explicitly selected test app.
Never print token/key, auto-install apps, grant permissions or send a page.
"""
import os
from pathlib import Path
import re
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
ADB = Path("/Users/haoyuzuo/Library/Android/sdk/platform-tools/adb")
DEVICE = "2AS0221B09001601"
PACKAGE = "com.kandong.compat.dev"


def adb(*args, data=None):
    result=subprocess.run([str(ADB),"-s",DEVICE,*args],input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
    if result.returncode:
        raise SystemExit("Device provisioning failed; no credentials were displayed.")
    return result.stdout


def main():
    if adb("get-state").strip()!=b"device" or adb("shell","getprop","ro.product.model").strip()!=b"NAM-LX9":
        raise SystemExit("Expected connected NAM-LX9 test phone.")
    path=ROOT/".local/translation/relay-token"
    s=path.lstat()
    if not stat.S_ISREG(s.st_mode) or s.st_uid!=os.getuid() or s.st_mode & 0o077 or s.st_size!=64 or s.st_nlink!=1:
        raise SystemExit("Private relay token unavailable.")
    token=path.read_bytes()
    if not re.fullmatch(rb"[a-f0-9]{64}",token): raise SystemExit("Invalid relay token.")
    adb("reverse","tcp:18741","tcp:18741")
    adb("shell","run-as",PACKAGE,"mkdir","-p","files")
    adb("shell","run-as",PACKAGE,"sh","-c","'umask 077; cat > files/relay-token'",data=token)
    if adb("shell","run-as",PACKAGE,"stat","-c","%s","files/relay-token").strip()!=b"64":
        raise SystemExit("Token provisioning not verified.")
    print("USB relay configured for KanDong development app; no page has been sent.")


if __name__=="__main__": main()
