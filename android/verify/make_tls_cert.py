#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成矩阵 mock 用的自签证书(SAN 含 10.0.2.2 + 127.0.0.1 + localhost)。

为什么不入库一份现成的:
  · 证书有有效期 —— 入库的自签证书某天会过期,CI 上的表现是「发版突然卡在 TLS 校验失败」,
    而且那时没人会想到是证书到期(实测上一份只签了 365 天);
  · 私钥不该进仓库,哪怕它只用于本地自签端点。

`verify/mock_matrix_tls.py` 缺证书时会自动调这里,所以本地和 CI 都是
「第一次跑到就自动生成」,不存在「忘了准备证书」这种失败。
"""
import os
import shutil
import subprocess
import sys

CERT = 'verify/tls/cert.pem'
KEY = 'verify/tls/key.pem'
DAYS = '3650'


def ensure(force=False):
    """没有证书就生成一份;返回是否真的生成了。"""
    if not force and os.path.exists(CERT) and os.path.exists(KEY):
        return False
    if not shutil.which('openssl'):
        raise SystemExit('没有 openssl,生成不了自签证书(本地用 Git Bash 自带的那份;CI 镜像里有)')
    os.makedirs('verify/tls', exist_ok=True)
    subprocess.run(
        ['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
         '-keyout', KEY, '-out', CERT, '-days', DAYS,
         '-subj', '/CN=10.0.2.2',
         '-addext', 'subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost'],
        check=True, capture_output=True)
    print('已生成自签证书 %s(有效期 %s 天,SAN 含 10.0.2.2)' % (CERT, DAYS), flush=True)
    return True


if __name__ == '__main__':
    ensure(force='--force' in sys.argv)
