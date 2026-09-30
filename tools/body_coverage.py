"""统计设备上缓存下来的正文覆盖度：首页搜索扩到正文之后，实际有多少内容搜得到。"""
import base64
import io
import os
import struct
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PKG = "com.example.feedreader"
# 多台设备时用它选机器；只接一台时留空即可。
SERIAL = os.environ.get("MYFEED_ADB_SERIAL", "")


def run(cmd, timeout=60):
    r = subprocess.run(["adb"] + (["-s", SERIAL] if SERIAL else []) + cmd, capture_output=True, timeout=timeout)
    if r.returncode != 0:
        raise SystemExit("adb 失败：%s\n%s" % (" ".join(cmd), r.stderr.decode("utf-8", "replace")))
    return r.stdout


def read_str(f):
    (n,) = struct.unpack(">i", f.read(4))
    if n < 0 or n > 4 * 1024 * 1024:
        raise ValueError("字段长度异常 %d" % n)
    return f.read(n).decode("utf-8")


def decode(data):
    f = io.BytesIO(data)
    magic, version = struct.unpack(">ii", f.read(8))
    if magic != 0x4D46_4348 or version != 2:
        return None
    read_str(f)  # sourceId（文件名派生，条目里另有一份）
    struct.unpack(">q", f.read(8))
    (count,) = struct.unpack(">i", f.read(4))
    out = []
    for _ in range(count):
        a = {}
        a["id"] = read_str(f)
        a["title"] = read_str(f)
        a["excerpt"] = read_str(f)
        a["body"] = read_str(f)
        a["link"] = read_str(f)
        a["author"] = read_str(f)
        a["sourceId"] = read_str(f)
        a["sourceName"] = read_str(f)
        a["category"] = read_str(f)
        a["publishedAt"] = struct.unpack(">q", f.read(8))[0]
        a["publishedRaw"] = read_str(f)
        out.append(a)
    return out


def main():
    names = [n for n in run(["exec-out", "run-as", PKG, "ls", "files/feed_cache"]).decode().split()
             if n.endswith(".feed")]
    lines = []
    total_articles = with_body = 0
    body_chars = 0
    for n in sorted(names):
        arts = decode(base64.b64decode(run(["exec-out", "run-as", PKG, "base64", "files/feed_cache/" + n])))
        if arts is None:
            lines.append("%-28s 版本/魔数不对，跳过" % n)
            continue
        wb = [a for a in arts if len(a["body"]) > 200]
        chars = sum(len(a["body"]) for a in arts)
        total_articles += len(arts)
        with_body += len(wb)
        body_chars += chars
        src = arts[0]["sourceName"] if arts else "?"
        longest = max((len(a["body"]) for a in arts), default=0)
        lines.append("%-14s %3d 条 · 正文>200 字 %3d 条 · 合计 %7d 字 · 最长 %5d 字"
                     % (src, len(arts), len(wb), chars, longest))
    head = "文件 %d 个 · 条目 %d 篇 · 其中正文超 200 字的 %d 篇（%.1f%%）· 正文字符合计 %d\n" % (
        len(names), total_articles, with_body, 100.0 * with_body / max(total_articles, 1), body_chars)
    with io.open("build/body-coverage.txt", "w", encoding="utf-8") as fh:
        fh.write(head)
        fh.write("\n".join(lines) + "\n")
    print("wrote build/body-coverage.txt")
    print("files=%d articles=%d with_body=%d" % (len(names), total_articles, with_body))


if __name__ == "__main__":
    main()
