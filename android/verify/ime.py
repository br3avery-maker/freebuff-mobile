#!/usr/bin/env python3
"""往当前聚焦的输入框输入任意文本(含中文),用无界面 IME AdbKeyboard。

为什么不用 `input text`:adb 的 `input text` 只吃 ASCII,中文会变成乱码;
UI 上的 Compose 输入框也不可靠地接收 `input keyevent` 组合。AdbKeyboard 是
无界面 IME —— 有真实输入连接、不弹键盘、支持广播注入任意 Unicode。

用法:
  python verify/ime.py 你好,世界
  python verify/ime.py --restore      # 切回原来的输入法
"""
import base64
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adbenv  # noqa: E402
ADB = adbenv.ADB
ADBKB = 'com.android.adbkeyboard/.AdbIME'


def sh(*a):
    try:
        return subprocess.run([ADB, 'shell', *a], capture_output=True,
                              timeout=30).stdout.decode('utf-8', 'ignore')
    except subprocess.TimeoutExpired:
        print('!! adb timeout', a)
        return ''


def current_ime():
    out = sh('settings', 'get', 'secure', 'default_input_method')
    return out.strip()


def activate():
    sh('ime', 'enable', ADBKB)
    sh('ime', 'set', ADBKB)


def has_adb_keyboard():
    """设备上有没有 AdbKeyboard:CI 的模拟器没装它,得走 ASCII 回退。"""
    return 'com.android.adbkeyboard' in sh('ime', 'list', '-s')


def type_text(text):
    if not has_adb_keyboard():
        # 没有 AdbKeyboard 时退回 `input text`(只吃 ASCII)。矩阵提示词是 `case xxx`,
        # 全是 ASCII,够用;真出现中文就明说会乱码,而不是悄悄写错东西。
        if not text.isascii():
            print('!! 没有 AdbKeyboard,非 ASCII 文本在端上会乱码:', text[:20])
        if len(text) > 30:
            # 见 docs/backend-integration.md:`input text` 长文本会吞尾巴(实测 57 字符只落地 40)
            print('!! 没有 AdbKeyboard,`input text` %d 字符可能吞尾巴' % len(text))
        sh('input', 'text', text.replace(' ', '%s'))
        print('typed', len(text), 'chars (input text)')
        return
    activate()
    b64 = base64.b64encode(text.encode('utf-8')).decode('ascii')
    sh('am', 'broadcast', '-a', 'ADB_INPUT_B64', '--es', 'msg', b64)
    print('typed', len(text), 'chars')


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--restore':
        sh('ime', 'set', 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME')
        print('ime restored')
    else:
        type_text(sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read())
