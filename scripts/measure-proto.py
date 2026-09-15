#!/usr/bin/env python3
"""驱动无头 Chrome 打开原型页，取回页内指标并截图。

为什么不用 `--dump-dom` + `--virtual-time-budget`（P0 的做法）：

1. 无头模式下一轮能拿到的 rAF 帧数不定（实测 1-3 帧），页面里的多步测量跑不完；
2. 虚拟时间会让 `performance.now()` 在同步任务里不推进——用忙碌等待计时会直接死循环
   （实测卡死），CPU 密集段的耗时也量不准。

所以这里用 CDP（DevTools 协议）驱动一个**真实时钟**的 Chrome：导航、等页面自报就绪、
按脚本执行交互、取指标、截图。页面需要暴露（见 `scripts/make-proto-page-p1.py`）：

    window.__PROTO = {
      ready: Promise,               // 首帧与首轮测量完成
      report: () => object,         // 指标快照
      run: (name, arg) => Promise,  // 脚本化交互（切布局、切筛选、切视图…）
    }

用法：

    python scripts/measure-proto.py --html build/proto/g6-p1-small.html \
        --shot build/proto/g6-p1-small.png --metrics build/proto/g6-p1-small.metrics.json \
        --call "window.__PROTO.run('layout','force')"

CDP 部分只用标准库（socket 上手写 WebSocket 帧），因为仓库不引入 pip 依赖。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REPO = Path(__file__).resolve().parent.parent

BROWSER_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
]


def resolve_browser() -> str:
    override = os.environ.get("SYMLPLOT_BROWSER")
    if override and Path(override).is_file():
        return override
    for candidate in BROWSER_CANDIDATES:
        if Path(candidate).is_file():
            return candidate
    raise SystemExit("未找到浏览器：装 Chrome/Edge，或用 SYMLPLOT_BROWSER 指定")


class WebSocket:
    """只支持文本帧的最小 WebSocket 客户端，够 CDP 用。"""

    def __init__(self, url: str, timeout: float = 30.0):
        match = re.match(r"ws://([^:/]+):(\d+)(/.*)$", url)
        if not match:
            raise ValueError("不支持的 WebSocket 地址: %s" % url)
        host, port, path = match.group(1), int(match.group(2)), match.group(3)
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        handshake = (
            "GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, host, port, key)
        )
        self.sock.sendall(handshake.encode("ascii"))
        header = b""
        while b"\r\n\r\n" not in header:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise IOError("WebSocket 握手失败")
            header += chunk
        if b"101" not in header.split(b"\r\n")[0]:
            raise IOError("WebSocket 握手被拒绝: %s" % header.split(b"\r\n")[0])
        expect = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        ).decode("ascii")
        if expect.encode("ascii") not in header:
            raise IOError("WebSocket 握手校验失败")
        self._buffer = b""
        self._id = 0

    def _read_exact(self, count: int) -> bytes:
        while len(self._buffer) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise IOError("WebSocket 连接已关闭")
            self._buffer += chunk
        data, self._buffer = self._buffer[:count], self._buffer[count:]
        return data

    def send(self, text: str) -> None:
        payload = text.encode("utf-8")
        header = bytearray([0x81])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", length)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv(self) -> str:
        chunks = []
        while True:
            first, second = self._read_exact(2)
            fin, opcode = first & 0x80, first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read_exact(8))[0]
            if second & 0x80:
                mask = self._read_exact(4)
                data = self._read_exact(length)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            else:
                data = self._read_exact(length)
            if opcode == 0x9:
                self.sock.sendall(bytes([0x8A, 0x80]) + os.urandom(4))
                continue
            if opcode == 0x8:
                raise IOError("WebSocket 被对端关闭")
            chunks.append(data)
            if fin:
                return b"".join(chunks).decode("utf-8", errors="replace")

    def call(self, method: str, params: dict | None = None, timeout: float = 60.0) -> dict:
        self._id += 1
        message_id = self._id
        self.send(json.dumps({"id": message_id, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while True:
            if time.time() > deadline:
                raise TimeoutError("CDP 调用超时: %s" % method)
            message = json.loads(self.recv())
            if message.get("id") != message_id:
                continue
            if "error" in message:
                raise IOError("CDP 错误 %s: %s" % (method, message["error"]))
            return message.get("result", {})

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


class Browser:
    def __init__(self, width: int, height: int, profile_dir: Path):
        self.profile_dir = profile_dir
        self.width, self.height = width, height
        self.process = None
        self.ws = None

    def __enter__(self) -> "Browser":
        self.profile_dir.mkdir(parents=True, exist_ok=True)
        args = [
            resolve_browser(), "--headless=new", "--disable-gpu", "--no-first-run",
            "--no-default-browser-check", "--remote-debugging-port=0",
            "--user-data-dir=%s" % self.profile_dir,
            "--window-size=%d,%d" % (self.width, self.height),
            "--hide-scrollbars", "--allow-file-access-from-files", "about:blank",
        ]
        self.process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        port_file = self.profile_dir / "DevToolsActivePort"
        deadline = time.time() + 30
        while time.time() < deadline:
            if port_file.is_file():
                content = port_file.read_text(encoding="utf-8").splitlines()
                if content and content[0].strip():
                    self._connect(int(content[0].strip()))
                    return self
            if self.process.poll() is not None:
                raise SystemExit("浏览器提前退出（exit=%s）" % self.process.returncode)
            time.sleep(0.1)
        raise SystemExit("等不到 DevToolsActivePort，浏览器没起来")

    def _connect(self, port: int) -> None:
        deadline = time.time() + 20
        target = None
        while time.time() < deadline:
            try:
                with urllib.request.urlopen("http://127.0.0.1:%d/json/list" % port, timeout=2) as response:
                    targets = json.loads(response.read().decode("utf-8"))
                pages = [t for t in targets if t.get("type") == "page"]
                if pages:
                    target = pages[0]
                    break
            except Exception:
                time.sleep(0.2)
        if target is None:
            raise SystemExit("找不到可用的 page target")
        self.ws = WebSocket(target["webSocketDebuggerUrl"])

    def __exit__(self, *exc) -> None:
        if self.ws:
            try:
                self.ws.call("Browser.close", timeout=5)
            except Exception:
                pass
            self.ws.close()
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
        shutil.rmtree(self.profile_dir, ignore_errors=True)


def evaluate(ws: WebSocket, expression: str, await_promise: bool = False, timeout: float = 180.0):
    result = ws.call("Runtime.evaluate", {
        "expression": expression,
        "awaitPromise": await_promise,
        "returnByValue": True,
        "userGesture": True,
    }, timeout=timeout)
    if result.get("exceptionDetails"):
        details = result["exceptionDetails"]
        description = (details.get("exception") or {}).get("description") or details.get("text")
        raise SystemExit("页面内执行出错: %s" % description)
    return (result.get("result") or {}).get("value")


def wait_ready(ws: WebSocket, timeout: float) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if evaluate(ws, "!!(window.__PROTO && window.__PROTO.ready)", timeout=15):
            evaluate(ws, "window.__PROTO.ready", await_promise=True, timeout=timeout)
            return
        time.sleep(0.2)
    raise SystemExit("页面没有暴露 window.__PROTO.ready（超时 %ss）" % timeout)


DIAGNOSTIC = r"""
(function () {
  function text(id) { const el = document.getElementById(id); return el ? el.textContent : null; }
  let report = null;
  try { report = (window.__PROTO && window.__PROTO.report) ? window.__PROTO.report() : null; } catch (error) {
    report = { reportError: String(error && error.message) };
  }
  return JSON.stringify({
    hasProto: !!window.__PROTO,
    status: text('status'),
    counts: text('counts'),
    timings: text('timings'),
    engine: text('engine'),
    report: report,
  });
})()
"""


def diagnose(ws: WebSocket) -> str:
    """页面没自报就绪时，至少把 DOM 上的状态与错误读回来——否则只能看到"超时"。"""
    try:
        raw = evaluate(ws, DIAGNOSTIC, timeout=20)
        return json.dumps(json.loads(raw), ensure_ascii=False, indent=1) if raw else "(诊断为空)"
    except Exception as error:
        return "(诊断失败: %s)" % error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--html", type=Path, required=True)
    parser.add_argument("--metrics", type=Path)
    parser.add_argument("--shot", type=Path)
    parser.add_argument("--call", action="append", default=[],
                        help="页面就绪后按顺序执行的 JS 表达式（可重复）")
    parser.add_argument("--width", type=int, default=1440)
    parser.add_argument("--height", type=int, default=900)
    parser.add_argument("--ready-timeout", type=float, default=300.0)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    html = args.html if args.html.is_absolute() else (REPO / args.html)
    if not html.is_file():
        raise SystemExit("找不到页面: %s" % html)
    url = "file:///" + str(html).replace("\\", "/")
    profile = REPO / "build" / ("chrome-cdp-" + hashlib.sha1(str(html).encode()).hexdigest()[:8])

    with Browser(args.width, args.height, profile) as browser:
        ws = browser.ws
        ws.call("Page.enable")
        ws.call("Runtime.enable")
        ws.call("Emulation.setDeviceMetricsOverride", {
            "width": args.width, "height": args.height, "deviceScaleFactor": 1, "mobile": False,
        })
        ws.call("Page.navigate", {"url": url})
        try:
            wait_ready(ws, args.ready_timeout)
        except SystemExit as error:
            print("%s\n页面诊断：\n%s" % (error, diagnose(ws)), file=sys.stderr)
            return 2

        for expression in args.call:
            evaluate(ws, expression, await_promise=True)

        raw = evaluate(ws, "JSON.stringify(window.__PROTO.report())")
        report = json.loads(raw) if raw else {}
        report["html"] = html.name
        report["htmlKB"] = round(html.stat().st_size / 1024, 1)
        report["measuredAt"] = time.strftime("%Y-%m-%dT%H:%M:%S")

        if args.metrics:
            out = args.metrics if args.metrics.is_absolute() else (REPO / args.metrics)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        if args.shot:
            shot = args.shot if args.shot.is_absolute() else (REPO / args.shot)
            shot.parent.mkdir(parents=True, exist_ok=True)
            data = ws.call("Page.captureScreenshot", {"format": "png"}, timeout=180)
            shot.write_bytes(base64.b64decode(data["data"]))

        if not args.quiet:
            print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
