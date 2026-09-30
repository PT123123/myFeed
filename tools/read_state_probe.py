"""设备侧已读记录探针：从平板的 feed_cache 里取两个真实 article.id，造一份 read_state.bin。

一次跑完：从设备缓存取真实 article.id -> 本机按同格式拼文件 -> 推给设备 -> 字节回读对账。

    adb -s <序列号> shell am force-stop com.example.feedreader   # 先停，避免和应用抢文件
    python tools/read_state_probe.py
    adb -s <序列号> logcat -c && adb -s <序列号> shell am start -n com.example.feedreader/.MainActivity
    adb -s <序列号> logcat -d | grep FeedReadState      # 期望「已读记录 3 条」
    adb -s <序列号> shell run-as com.example.feedreader rm files/read_state.bin   # 收尾

推送走 `echo <b64> | base64 -d`：`adb exec-out` 不转发 stdin，用管道喂进去会得到一个
0 字节文件（症状是应用报「已读记录 0 条」，看着像解析坏了，其实根本没写进去）。
"""
import base64
import io
import struct
import subprocess
import sys
import os

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PKG = "com.example.feedreader"
# 多台设备时用它选机器；只接一台时留空即可。
SERIAL = os.environ.get("MYFEED_ADB_SERIAL", "")
MAGIC = 0x4D46_5244  # "MFRD"
VERSION = 1


def device_now_ms():
    """按设备时钟算「现在」。readAt 只要落在 90 天窗口外就会被 prune 掉，
    写死一个时间戳等于过几个月再跑就直接假失败。"""
    out = adb("shell", "date", "+%s").decode().strip()
    return int(out) * 1000


def adb(*args, data=None):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    r = subprocess.run(cmd, input=data, capture_output=True)
    if r.returncode != 0:
        raise SystemExit("adb 失败: %s\n%s" % (" ".join(cmd), r.stderr.decode("utf-8", "replace")))
    return r.stdout


def run_as(shell_cmd, data=None):
    return adb("exec-out", "run-as", PKG, "sh", "-c", shell_cmd, data=data)


def write_str(buf, s):
    b = s.encode("utf-8")
    buf.write(struct.pack(">i", len(b)))
    buf.write(b)


def read_str(f):
    (n,) = struct.unpack(">i", f.read(4))
    if n < 0 or n > 4 * 1024 * 1024:
        raise ValueError("字段长度异常 %d" % n)
    return f.read(n).decode("utf-8")


def decode_cache(data):
    f = io.BytesIO(data)
    magic, version = struct.unpack(">ii", f.read(8))
    assert magic == 0x4D46_4348, "不是缓存文件 %08x" % magic
    assert version == 2, "缓存版本 %d" % version
    source_id = read_str(f)
    fetched = struct.unpack(">q", f.read(8))[0]
    (count,) = struct.unpack(">i", f.read(4))
    articles = []
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
        articles.append(a)
    return source_id, fetched, articles


def make():
    names = run_as("ls files/feed_cache").decode().split()
    names = [n for n in names if n.endswith(".feed")]
    picked = []
    for n in names:
        raw = base64.b64decode(run_as("base64 files/feed_cache/%s" % n))
        source_id, _, articles = decode_cache(raw)
        if len(articles) >= 2:
            picked = [(a["id"], a["title"][:40], source_id) for a in articles[:3]]
            if picked:
                break
    if not picked:
        raise SystemExit("设备上没有一个缓存文件有 2 条以上条目")
    ids = [p[0] for p in picked]
    now = device_now_ms()
    buf = io.BytesIO()
    buf.write(struct.pack(">iii", MAGIC, VERSION, len(ids)))
    for i, _ in enumerate(ids):
        write_str(buf, ids[i])
        buf.write(struct.pack(">q", now - i * 60_000))
    payload = buf.getvalue()
    os.makedirs("build", exist_ok=True)
    with open("build/read_state.bin", "wb") as fh:
        fh.write(payload)
    with io.open("build/probe_ids.txt", "w", encoding="utf-8") as fh:
        fh.write("ids=%d bytes=%d\n" % (len(ids), len(payload)))
        for src, i, t in [(p[2], p[0], p[1]) for p in picked]:
            fh.write("%s | %s | %s\n" % (src, i, t))
    # 送进设备：base64 塞进命令行让设备自己解 —— exec-out 不转发 stdin，
    # 用 data= 喂进去只会得到一个 0 字节文件
    b64 = base64.b64encode(payload).decode("ascii")
    run_as("echo %s | base64 -d > files/read_state.bin" % b64)
    on_dev = run_as("ls -l files/read_state.bin").decode("utf-8", "replace").strip()
    back = base64.b64decode(run_as("base64 files/read_state.bin"))
    assert back == payload, "设备上的字节和本机不一致：\n%s" % on_dev
    print("pushed: %s" % on_dev)
    print("wrote build/read_state.bin (%d bytes, %d ids)" % (len(payload), len(ids)))
    return picked


if __name__ == "__main__":
    for row in make():
        print("%s | %s | %s" % row)
