#!/usr/bin/env python3
"""Interactive local setup only; no requests, no displayed or logged credential."""
import getpass
import sys
from deepl_credentials import save_key

if not sys.stdin.isatty():
    raise SystemExit("请在 Mac 终端中运行此配置工具。")
print("看懂 · 配置 DeepL\n请从 DeepL 账号的 API Keys & Limits 页面复制密钥。")
print("粘贴后按回车；输入不会显示。密钥仅存于本机项目的私有文件，不上传、不写入 Git 或 APK。")
try:
    save_key(getpass.getpass("DeepL API 密钥："))
except (ValueError, OSError):
    raise SystemExit("未保存：格式或本地文件权限不符；密钥未显示。")
except (KeyboardInterrupt, EOFError):
    raise SystemExit("已取消，没有配置。")
print("已安全保存。请回到 Codex 告诉我“已配置”，我再验证账号和合成翻译页面。")
