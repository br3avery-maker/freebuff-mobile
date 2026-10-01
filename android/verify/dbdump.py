#!/usr/bin/env python3
"""拉设备上的 Room 库到 verify/dbtmp/ 并打印最近的会话/消息,便于核对落盘内容。

用法:
  python verify/dbdump.py messages [n]    最近 n 条消息(text/steps/reasoning/tools)
  python verify/dbdump.py sessions
  python verify/dbdump.py sql "<SQL>"

注意:Windows 上同一个文件被两个 sqlite 句柄同时打开会报 EINVAL,
所以这里维护单例连接 —— 每次 pull() 先关掉上一个再拉。
"""
import os
import sqlite3
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adbenv  # noqa: E402  本地 .toolchain / CI 的 PATH 共用一份 adb 解析
ADB = adbenv.ADB
PKG = 'com.freebuff.mobile'
OUT = os.path.abspath('verify/dbtmp')  # 每个进程一个子目录:避免多进程抢同一个 sqlite 文件(Windows 会 EINVAL)
_conn = None


def pull():
    """拉一份最新库并返回连接(自动关掉上一次的连接,避免文件锁冲突)。"""
    global _conn
    if _conn is not None:
        try:
            _conn.close()
        except Exception:
            pass
        _conn = None
    run_dir = os.path.join(OUT, 'p%d' % os.getpid())
    os.makedirs(run_dir, exist_ok=True)
    for f in ('freebuff.db', 'freebuff.db-wal', 'freebuff.db-shm'):
        dst = os.path.join(run_dir, f)
        try:
            _root = b'uid=0' in subprocess.run([ADB, 'shell', 'id'], capture_output=True,
                                               timeout=20).stdout
        except subprocess.TimeoutExpired:
            _root = False
        _cmd = ([ADB, 'exec-out', 'cat', '/data/data/%s/databases/%s' % (PKG, f)]
                if _root
                else [ADB, 'exec-out', 'run-as', PKG, 'cat', 'databases/' + f])
        p = None
        for _try in range(2):
            try:
                p = subprocess.run(_cmd, capture_output=True, timeout=60)
                break
            except subprocess.TimeoutExpired:
                print('!! dbdump pull timeout', f)
        if p is None:
            raise RuntimeError('dbdump pull timeout: ' + f)
        with open(dst, 'wb') as fh:
            fh.write(p.stdout)
    _conn = sqlite3.connect(os.path.join(run_dir, 'freebuff.db'))
    return _conn


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'messages'
    c = pull()
    c.row_factory = sqlite3.Row
    if cmd == 'sql':
        for r in c.execute(sys.argv[2]).fetchall():
            print(dict(r))
    elif cmd == 'sessions':
        for r in c.execute('select id, title, preview, time, createdAt from sessions order by rowid desc limit 12'):
            print(dict(r))
    else:
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 4
        for r in c.execute(f'select * from messages order by rowid desc limit {n}'):
            d = dict(r)
            print('=' * 70)
            print('role=%s  session=%s  text_len=%d' % (
                d.get('role'), (d.get('sessionId') or '')[-6:], len(d.get('text') or '')))
            print('reasoning_len=%s  steps=%s' % (len(d.get('reasoning') or ''), d.get('stepsJson')))
            print('---- text ----')
            print(d.get('text'))


if __name__ == '__main__':
    main()
