#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 mock_matrix 的 Handler 包一层 TLS 跑在 8899。

为什么需要它:release 包(未声明 usesCleartextTraffic)按 Android 默认**禁止明文 HTTP**,
所以 `http://10.0.2.2:8899` 在发布包上会被系统直接拦掉,矩阵跑不起来。
用自签名证书 + 模型里的 skipTLS(产品自带的「跳过 TLS 校验,仅用于本地自签名端点」)
就能在**未经修改的发布包**上跑同一套剧本 —— 剧本与断言完全复用 mock_matrix,不改一行。

用法(在 android/ 下,先确保 8899 没被明文版占着):
    python verify/mock_matrix_tls.py
证书:verify/tls/cert.pem + key.pem(自签,SAN 含 IP:10.0.2.2)。
"""
import os
import ssl
import sys
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.abspath('verify'))
import make_tls_cert  # noqa: E402
import mock_matrix  # noqa: E402

CERT = 'verify/tls/cert.pem'
KEY = 'verify/tls/key.pem'


def main():
    make_tls_cert.ensure()          # 证书过期/缺失是「发版突然 TLS 失败」的经典原因
    httpd = ThreadingHTTPServer(('0.0.0.0', mock_matrix.PORT), mock_matrix.Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print('matrix mock (TLS) listening on {}'.format(mock_matrix.PORT), flush=True)
    httpd.serve_forever()


if __name__ == '__main__':
    main()
