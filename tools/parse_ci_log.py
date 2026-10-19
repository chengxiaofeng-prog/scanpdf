#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 GitHub Actions 日志里提取真正的编译错误。

用法：
    python tools/parse_ci_log.py <日志.zip 或 .txt>
    python tools/parse_ci_log.py            # 自动找当前目录/下载目录下最新的 logs*.zip

输出：按「文件:行:列」聚合的 Kotlin/Java/Gradle 错误，以及失败的任务名。
"""
import io
import os
import re
import sys
import zipfile
import glob

# Kotlin: e: file:///home/runner/work/scanpdf/.../EditScreen.kt: (123, 45): Unresolved reference: foo
KT = re.compile(r"e: file://(?P<path>[^\s:]+\.kt):\s*\((?P<line>\d+),\s*(?P<col>\d+)\):\s*(?P<msg>.+)")
# Kotlin 老格式: e: /path/File.kt: (12, 3): error text
KT2 = re.compile(r"e: (?P<path>[^\s:]+\.kt):\s*\((?P<line>\d+),\s*(?P<col>\d+)\):\s*(?P<msg>.+)")
# Java/javac
JV = re.compile(r"(?P<path>[^\s:]+\.java):(?P<line>\d+):\s*(error|错误):\s*(?P<msg>.+)")
# Gradle 通用失败
FAIL = re.compile(r"^(FAILURE: Build failed.*|> Task .* FAILED|> Could not .*|Caused by: .*)$")

NOISE = (
    "Deprecated Gradle features",
    "See https://docs.gradle.org",
    "BUILD FAILED in",
    "Warning:",
    "w: file://",
)


def strip_prefix(path: str) -> str:
    """把 /home/runner/work/scanpdf/scanpdf/app/src/main/java/... 压成 app/src/main/java/..."""
    for marker in ("/app/src/", "/app/", "/gradle/", "/build.gradle"):
        i = path.find(marker)
        if i >= 0:
            return path[i + 1:]
    return path.split("/")[-1]


def read_lines(src: bytes):
    text = src.decode("utf-8", "replace")
    # Actions 日志每行带时间戳前缀：2026-10-19T03:12:45.1234567Z
    out = []
    for ln in text.splitlines():
        m = re.match(r"^\d{4}-\d{2}-\d{2}T[\d:.]+Z\s?", ln)
        out.append(ln[m.end():] if m else ln)
    return out


def collect(path: str):
    if path.lower().endswith(".zip"):
        lines = []
        with zipfile.ZipFile(path) as z:
            names = sorted(z.namelist())
            print(f"[i] 压缩包内 {len(names)} 个文件：")
            for n in names:
                if n.endswith("/"):
                    continue
                print(f"    - {n}")
            # 优先看 build 相关的那几个
            order = sorted(names, key=lambda n: (0 if "Build" in n or "build" in n else 1, n))
            for n in order:
                if not n.endswith((".txt", ".log")):
                    continue
                lines.append(f"\n===== {n} =====")
                lines.extend(read_lines(z.read(n)))
        return lines
    with open(path, "rb") as fh:
        return read_lines(fh.read())


def main():
    if len(sys.argv) > 1:
        path = sys.argv[1]
    else:
        cands = []
        for pat in ("logs*.zip", "*.zip", os.path.expanduser("~/Downloads/logs*.zip"),
                    os.path.expanduser("~/下载/logs*.zip")):
            cands += glob.glob(pat)
        cands = [c for c in cands if os.path.isfile(c)]
        if not cands:
            print("用法: python tools/parse_ci_log.py <日志.zip|.txt>")
            print("（在 GitHub Actions 运行页右上角 ... → Download log archive 拿到 zip）")
            return 2
        path = max(cands, key=os.path.getmtime)
        print(f"[i] 自动选用: {path}")

    lines = collect(path)

    errors = {}
    fails = []
    for ln in lines:
        s = ln.strip()
        if not s or any(n in s for n in NOISE):
            continue
        for rx in (KT, KT2):
            m = rx.search(s)
            if m:
                key = (strip_prefix(m.group("path")), int(m.group("line")), int(m.group("col")))
                errors.setdefault(key, []).append(m.group("msg").strip())
                break
        else:
            m = JV.search(s)
            if m:
                key = (strip_prefix(m.group("path")), int(m.group("line")), 0)
                errors.setdefault(key, []).append(m.group("msg").strip())
            elif FAIL.match(s):
                fails.append(s)

    if fails:
        print("\n=== 构建失败点 ===")
        for f in dict.fromkeys(fails):
            print("  " + f)

    if not errors:
        print("\n[x] 没抓到 Kotlin/Java 编译错误。可能是：")
        print("    - 失败发生在依赖下载阶段（看上面 FAILURE / Could not 行）")
        print("    - 失败发生在 Gradle 配置阶段（看 settings/build.gradle.kts 相关报错）")
        print("    - workflow 根本没跑（检查 .github/workflows 是否上传成功）")
        return 1

    print(f"\n=== 编译错误 {len(errors)} 处 ===")
    for (path, line, col), msgs in sorted(errors.items()):
        print(f"\n{path}:{line}:{col}")
        for m in dict.fromkeys(msgs):
            print(f"    {m}")

    print("\n=== 按文件汇总（先修错误数最多的文件）===")
    per = {}
    for (path, _, _), msgs in errors.items():
        per[path] = per.get(path, 0) + len(msgs)
    for path, n in sorted(per.items(), key=lambda kv: -kv[1]):
        print(f"  {n:3d}  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
