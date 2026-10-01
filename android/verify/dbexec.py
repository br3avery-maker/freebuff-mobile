#!/usr/bin/env python3
"""在**设备已停 App** 的前提下改设备上的 Room 库(拉回 → 执行 SQL → 推回)。

为什么不用 run-as + 设备端 sqlite3:模拟器里没有 sqlite3 可执行文件。
安全做法:强制停止 App → 取 db/-wal 到本地(本地打开会把 WAL 合并进主库)→
执行任意 SQL → 把合并后的单文件推回,并删掉设备上的 -wal/-shm(避免旧 WAL 覆盖新数据)。

用法:
  python verify/dbexec.py "update custom_models set base='http://10.0.2.2:8899/v1'"
  python verify/dbexec.py --print "select id,name,base from custom_models; select * from settings"
"""
import os
import shutil
import sqlite3
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adbenv  # noqa: E402  本地 .toolchain / CI 的 PATH 共用一份 adb 解析
ADB = adbenv.ADB
PKG = 'com.freebuff.mobile'
WORK = os.path.abspath('verify/dbwork')
DB = os.path.join(WORK, 'freebuff.db')


def adb(*a, timeout=60):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout


def pull():
    os.makedirs(WORK, exist_ok=True)
    if b'uid=0' not in subprocess.run([ADB, 'shell', 'id'], capture_output=True).stdout:
        adb('shell', 'run-as', PKG, 'mkdir', '-p', 'databases')
    for f in ('freebuff.db', 'freebuff.db-wal', 'freebuff.db-shm'):
        _cmd = ([ADB, 'exec-out', 'cat', '/data/data/%s/databases/%s' % (PKG, f)]
                if b'uid=0' in subprocess.run([ADB, 'shell', 'id'], capture_output=True).stdout
                else [ADB, 'exec-out', 'run-as', PKG, 'cat', 'databases/' + f])
        out = subprocess.run(_cmd, capture_output=True).stdout
        with open(os.path.join(WORK, f), 'wb') as fh:
            fh.write(out)


def push():
    # 先把合并后的库推回,再删 WAL —— 顺序反了会丢掉最新写入
    subprocess.run([ADB, 'push', DB, '/data/local/tmp/fb.db'], capture_output=True)
    if b'uid=0' in subprocess.run([ADB, 'shell', 'id'], capture_output=True).stdout:
        D = '/data/data/%s/databases' % PKG
        _u = adb('shell', 'stat', '-c', '%u', '/data/data/' + PKG).decode().strip()
        _g = adb('shell', 'stat', '-c', '%g', '/data/data/' + PKG).decode().strip()
        adb('shell', 'rm', '-f', D + '/freebuff.db-wal', D + '/freebuff.db-shm')
        adb('shell', 'cp', '/data/local/tmp/fb.db', D + '/freebuff.db')
        if _u:
            adb('shell', 'chown', _u + ':' + _g, D + '/freebuff.db')
        adb('shell', 'chmod', '660', D + '/freebuff.db')
        adb('shell', 'restorecon', '-R', '/data/data/' + PKG)
    else:
        adb('shell', 'run-as', PKG, 'rm', '-f', 'databases/freebuff.db-wal', 'databases/freebuff.db-shm')
        adb('shell', 'run-as', PKG, 'cp', '/data/local/tmp/fb.db', 'databases/freebuff.db')
    adb('shell', 'rm', '-f', '/data/local/tmp/fb.db')


def main():
    write = sys.argv[1] != '--print'
    sql = sys.argv[2] if not write else sys.argv[1]
    print('stopping app…')
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(1.5)
    os.makedirs('verify/dbbackup', exist_ok=True)
    pull()
    if os.path.exists(DB):
        shutil.copy(DB, 'verify/dbbackup/freebuff.db.pulled')
    c = sqlite3.connect(DB)
    for stmt in [s for s in sql.split(';') if s.strip()]:
        for r in c.execute(stmt).fetchall():
            print(r)
    c.commit()
    c.close()
    if write:
        push()
        print('pushed')
    print('done')


if __name__ == '__main__':
    main()
