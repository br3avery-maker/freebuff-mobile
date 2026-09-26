#!/usr/bin/env python3
"""dist/update.json(App 内「检查更新」的数据源)的生成与校验。

生成 —— 发版工作流用,把标签版本与提交标题写进清单并回推 main:
    python3 scripts/update-manifest.py 1.2.0 [--prev v1.1.0]
校验 —— CI 用,清单与产品版本号不允许漂移:
    python3 scripts/update-manifest.py --check

清单格式(UpdateRepository 解析):
    {"version": "1.2.0", "url": "https://github.com/<owner>/<repo>/releases/latest", "notes": ["..."]}

校验规则:
  * JSON 合法;version 为 x.y.z;url 为 https 链接;notes 是非空字符串数组
  * version 不得高于 android/version.properties 的 versionName
    (高于 = 所有用户都会看到一个永远装不上的更新)
  * version 低于 versionName 只警告:未发版的版本号提升属正常,发版工作流会自动对齐
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MANIFEST = REPO_ROOT / "dist" / "update.json"
VERSION_PROPS = REPO_ROOT / "android" / "version.properties"
FALLBACK_SLUG = "doubao01/freebuff-mobile"
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")


def fail(msg: str) -> None:
    print("::error::" + msg, file=sys.stderr)
    raise SystemExit(1)


def warn(msg: str) -> None:
    print("::warning::" + msg)


def app_version_name() -> str:
    """读 android/version.properties 的 versionName(版本号唯一来源)。"""
    if not VERSION_PROPS.exists():
        fail(f"缺少 {VERSION_PROPS} —— 版本号唯一来源,由 app 与 core:model 共同派生")
    for raw in VERSION_PROPS.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() == "versionName":
            version = value.strip()
            if not VERSION_RE.match(version):
                fail(f"android/version.properties 的 versionName 非法: '{version}'")
            return version
    fail("android/version.properties 里没有 versionName")


def repo_slug() -> str:
    """owner/repo:CI 用 GITHUB_REPOSITORY,本机从 origin 推导。"""
    slug = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if slug:
        return slug
    out = git("remote", "get-url", "origin")
    if out:
        url = out.strip().removesuffix(".git")
        if url.startswith("git@github.com:"):
            return url[len("git@github.com:"):]
        if "github.com/" in url:
            return url.split("github.com/", 1)[1]
    return FALLBACK_SLUG


def git(*args: str) -> str | None:
    try:
        done = subprocess.run(
            ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True
        )
        return done.stdout
    except (OSError, subprocess.CalledProcessError):
        return None


def resolve_ref(version: str, ref: str) -> str:
    """把版本号落到一个真实存在的 git 引用上:优先显式 --ref,其次 v<version>,最后 <version>。

    发版工作流跑在标签上,标签名带 v 前缀;直接拿裸版本号当 revision 会静默失败,
    更新说明就退化成默认文案。
    """
    for cand in ([ref] if ref else []) + [f"v{version}", version]:
        if git("rev-parse", "--verify", f"{cand}^{{commit}}"):
            return cand
    # 解析不出来时退回 HEAD:直接把不存在的名字交给 git log 会被当成路径,
    # 拿到的是一堆不相干的提交标题。
    warn(f"git 里找不到 {ref or version} 对应的提交,更新说明改用 HEAD 的最近提交")
    return "HEAD"


def notes_from_git(prev: str, ref: str) -> list[str]:
    """更新说明 = 上一个标签到本次版本之间的提交标题。"""
    if prev:
        log = git("log", "--pretty=format:%s", f"{prev}..{ref}")
        notes = [line.strip() for line in (log or "").splitlines() if line.strip()]
        if notes:
            return notes
    log = git("log", "-n", "20", "--pretty=format:%s", ref)
    return [line.strip() for line in (log or "").splitlines() if line.strip()]


def parse_version(v: str) -> tuple[int, ...]:
    return tuple(int(p) for p in v.split("."))


def read_version(path: Path) -> str:
    """读清单里的版本号;文件不存在/不可解析时返回空串。"""
    if not path.exists():
        return ""
    try:
        value = json.loads(path.read_text(encoding="utf-8")).get("version", "")
    except (json.JSONDecodeError, UnicodeDecodeError, AttributeError):
        return ""
    return value if isinstance(value, str) and VERSION_RE.match(value) else ""


def cmd_write(version: str, prev: str, ref: str = "", out: Path = MANIFEST) -> None:
    if not VERSION_RE.match(version):
        fail(f"版本号必须是 x.y.z 形态: '{version}'")
    current = read_version(out)
    if current and parse_version(version) < parse_version(current):
        fail(
            f"拒绝把清单降级:{out.name} 已是 v{current},本次却是 v{version} —— "
            "补发旧标签会让已升级的用户看到「新版本」;确实要回滚请手工改该文件"
        )
    resolved = resolve_ref(version, ref)
    notes = notes_from_git(prev, resolved) or ["自动发版"]
    payload = {
        "version": version,
        "url": f"https://github.com/{repo_slug()}/releases/latest",
        "notes": notes,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"{out} -> {payload['version']}, {len(notes)} notes "
        f"(区间 {'<root>' if not prev else prev}..{resolved}), url={payload['url']}"
    )


def cmd_check(path: Path = MANIFEST) -> None:
    if not path.exists():
        fail("dist/update.json 不存在 —— 它是 App 内「检查更新」的数据源")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        fail(f"dist/update.json 不是合法 JSON: {e}")
    if not isinstance(data, dict):
        fail("dist/update.json 顶层必须是对象")

    version = data.get("version")
    if not isinstance(version, str) or not VERSION_RE.match(version):
        fail(f"dist/update.json 的 version 必须是 x.y.z 形态: {version!r}")

    url = data.get("url", "")
    if not isinstance(url, str) or not url.startswith("https://"):
        fail(f"dist/update.json 的 url 必须是 https 链接(当前 {url!r})")

    notes = data.get("notes")
    if not isinstance(notes, list) or not notes or not all(
        isinstance(n, str) and n.strip() for n in notes
    ):
        fail("dist/update.json 的 notes 必须是非空字符串数组")

    app = app_version_name()
    if parse_version(version) > parse_version(app):
        fail(
            f"dist/update.json 的 v{version} 高于产品版本 v{app}"
            " —— 用户会看到一个永远装不上的更新。发版请用发布工作流(它会从标签重建清单)"
        )
    if version != app:
        warn(f"dist/update.json 仍是 v{version},产品版本已是 v{app}(未发版属正常,发版后自动对齐)")
    print(f"dist/update.json OK: v{version}, {len(notes)} notes, url={url} (产品版本 v{app})")


def main() -> None:
    parser = argparse.ArgumentParser(description="生成/校验 dist/update.json")
    parser.add_argument("version", nargs="?", help="写入的版本号(x.y.z,通常等于发版标签去掉 v)")
    parser.add_argument("--prev", default="", help="更新说明的起点标签,如 v1.1.0")
    parser.add_argument("--ref", default="", help="本次版本的 git 引用(默认按 v<version> 探测)")
    parser.add_argument("--check", action="store_true", help="校验现有 dist/update.json 后退出")
    parser.add_argument("--out", default=str(MANIFEST), help="清单路径(默认 dist/update.json)")
    args = parser.parse_args()

    path = Path(args.out)
    if args.check:
        cmd_check(path)
        return
    if not args.version:
        parser.error("需要给出版本号,或用 --check 校验")
    cmd_write(args.version, args.prev, args.ref, path)


if __name__ == "__main__":
    main()
