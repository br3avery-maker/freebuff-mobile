#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""adb 路径解析:本地工装链(.toolchain)与 CI(PATH)共用一份。

本地是 Windows 的 `.toolchain/sdk/platform-tools/adb.exe`;CI 的 Linux runner 上
adb 由预装的 Android SDK 提供、在 PATH 里,没有 .toolchain。
优先级:环境变量 ADB > .toolchain 里的 > PATH 里的。

各脚本一律 `ADB = adbenv.ADB`,不要再各自硬编码 .exe(那样在 CI 上必挂)。
"""
import os
import shutil

_CANDIDATES = (
    '.toolchain/sdk/platform-tools/adb.exe',
    '.toolchain/sdk/platform-tools/adb',
)


def resolve():
    from_env = os.environ.get('ADB')
    if from_env:
        return from_env
    for rel in _CANDIDATES:
        p = os.path.abspath(rel)
        if os.path.exists(p):
            return p
    on_path = shutil.which('adb')
    if on_path:
        return on_path
    raise SystemExit('找不到 adb:设 ADB=<路径>,或把它放进 PATH(CI runner 预装的 SDK 里就有)')


ADB = resolve()
