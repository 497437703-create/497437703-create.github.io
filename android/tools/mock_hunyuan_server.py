#!/usr/bin/env python3
"""Stand-in for an on-prem SCH-900S Hunyuan deployment, for demos and integration tests without hardware.

Python 3 standard library only. Serves what AI 会记 calls when the "企业私有化 · SCH-900S" backend is
selected with 演示服务 off:

  GET  /v1/health                    gateway health (RemoteContract HealthDto)
  WS   /v1/asr/stream                streaming ASR: scripted captions (partial → final), "closed" on end
  POST /v1/speakers/resolve          voiceprint stub (no names)
  POST /v1/meetings/{id}/share       share link for the takeaway QR
  POST /v1/chat/completions          OpenAI-compatible Hunyuan endpoint: translation + minutes JSON
  GET  /v1/models                    lists the mock Hunyuan models

Every call requires `Authorization: Bearer <token>` (default `demo-token`); a wrong token returns 401 so
the app's "token rejected" error can be demonstrated too.

  python3 mock_hunyuan_server.py --port 8090 --token demo-token
  # Android emulator: 服务地址 http://10.0.2.2:8090, 访问令牌 demo-token
"""
import argparse
import base64
import hashlib
import json
import re
import socketserver
import struct
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler

SCRIPT = [
    ("S1", "今天我们主要讨论下季度的产品规划，先看一下目前的进度和风险。", "Today we'll discuss next quarter's product plan, starting with progress and risks."),
    ("S2", "语音转写模块已经完成联调，私有化部署的首字延迟在一秒左右。", "The transcription module has finished integration; first-word latency on-prem is about one second."),
    ("S3", "翻译和纪要都跑在 SCH-900S 的混元模型上，数据不出内网。", "Translation and minutes both run on Hunyuan models on the SCH-900S, so data stays on-prem."),
    ("S1", "好，那就定方案 B：月底演示用私有化部署。", "OK, we'll go with plan B: the end-of-month demo uses the on-prem deployment."),
    ("S2", "我来负责，周五前提交压测报告。", "I'll own it and submit the load-test report by Friday."),
    ("S3", "风险是 Arc B70 的驱动版本，需要和联想确认。", "The risk is the Arc B70 driver version; we need to confirm with Lenovo."),
]
TRANSLATIONS = {zh: en for _, zh, en in SCRIPT}
TOKEN = "demo-token"


def ws_frame(text):
    data = text.encode()
    header = bytes([0x81])
    n = len(data)
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + struct.pack(">H", n)
    else:
        header += bytes([127]) + struct.pack(">Q", n)
    return header + data


def read_ws_frame(rfile):
    head = rfile.read(2)
    if len(head) < 2:
        return None, None
    opcode = head[0] & 0x0F
    masked = head[1] & 0x80
    n = head[1] & 0x7F
    if n == 126:
        n = struct.unpack(">H", rfile.read(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", rfile.read(8))[0]
    mask = rfile.read(4) if masked else b"\0\0\0\0"
    payload = bytearray(rfile.read(n))
    for i in range(len(payload)):
        payload[i] ^= mask[i % 4]
    return opcode, bytes(payload)


def minutes_json(user_prompt):
    lines = re.findall(r"^\[(\d+)\] (\S+(?: \d+)?) \S+ (.+)$", user_prompt, re.M)
    def refs(words):
        return [int(n) for n, _, text in lines if any(w in text for w in words)][:3]
    def items(words):
        return [{"text": text, "refs": [int(n)]} for n, _, text in lines if any(w in text for w in words)][:6]
    first = lines[0][2] if lines else "会议内容"
    todos = [{"text": text, "refs": [int(n)], "owner": who, "due": "周五" if "周五" in text else None}
             for n, who, text in lines if any(w in text for w in ("我来", "负责", "提交"))][:6]
    return {
        "overview": {"text": f"本次会议共 {len(lines)} 段发言，围绕「{first[:24]}」展开，确认了方案与分工。", "refs": refs(("讨论", "规划"))},
        "cards": {
            "TOPICS": items(("讨论", "规划", "进度")),
            "DECISIONS": items(("定", "决定", "方案")),
            "TODOS": todos,
            "RISKS": items(("风险", "延期", "驱动")),
            "PROGRESS": items(("完成", "联调")),
            "ISSUES": items(("风险", "问题")),
        },
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[mock-sch900s]", self.command, self.path, "->", args[1] if len(args) > 1 else "")

    def authorized(self):
        if self.headers.get("Authorization") == f"Bearer {TOKEN}":
            return True
        self.send_json({"error": {"message": "invalid token", "type": "auth"}}, 401)
        return False

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self.path.startswith("/v1/asr/stream"):
            return self.asr_stream()
        if not self.authorized():
            return
        if self.path == "/v1/health":
            return self.send_json({
                "status": "ok", "modelVersion": "mock-sch900s-hunyuan",
                "asrLangs": ["zh", "en", "fr", "de", "ja"],
                "translationPairs": {"zh": ["en", "fr", "de", "ja"]},
                "templates": ["general", "project", "standup"], "speakerRecognition": True,
            })
        if self.path == "/v1/models":
            return self.send_json({"data": [{"id": "Hunyuan-MT-7B"}, {"id": "Hunyuan-A13B-Instruct"}]})
        self.send_json({"error": {"message": "not found"}}, 404)

    def do_POST(self):
        if not self.authorized():
            return
        req = self.body()
        if self.path == "/v1/chat/completions":
            messages = req.get("messages", [])
            prompt = messages[-1]["content"] if messages else ""
            system = messages[0]["content"] if len(messages) > 1 else ""
            if "会议纪要助手" in system:
                time.sleep(1.5)
                content = json.dumps(minutes_json(prompt), ensure_ascii=False)
            elif req.get("max_tokens") == 1:
                content = "pong"
            else:
                text = prompt.split("\n\n", 1)[-1]
                content = TRANSLATIONS.get(text, f"[{req.get('model')}] {text}")
            return self.send_json({"id": str(uuid.uuid4()), "model": req.get("model"),
                                   "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}]})
        if self.path == "/v1/speakers/resolve":
            return self.send_json({"clusterKey": f"{req.get('channel')}:{req.get('diarizationLabel') or 'U'}", "confidence": 0.9})
        m = re.match(r"^/v1/meetings/([^/]+)/share$", self.path)
        if m:
            host = self.headers.get("Host", "sch900s.local")
            return self.send_json({"url": f"http://{host}/s/{req.get('code')}?t={uuid.uuid4().hex[:12]}",
                                   "expiresAtEpochMs": int(time.time() * 1000) + 7 * 24 * 3600 * 1000})
        self.send_json({"error": {"message": "not found"}}, 404)

    def asr_stream(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        key = self.headers.get("Sec-WebSocket-Key", "")
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC11B65").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        lock = threading.Lock()
        state = {"open": True, "paused": False, "offset": 0, "t0": time.time()}

        def send(obj):
            with lock:
                if state["open"]:
                    self.wfile.write(ws_frame(json.dumps(obj, ensure_ascii=False)))
                    self.wfile.flush()

        def clock():
            return state["offset"] or int((time.time() - state["t0"]) * 1000)

        def speak():
            i = 0
            time.sleep(1.0)
            while state["open"]:
                spk, text, _ = SCRIPT[i % len(SCRIPT)]
                seg = f"seg-{i}"
                start = clock()
                for k in range(2, len(text), 3):
                    if not state["open"]:
                        return
                    while state["paused"] and state["open"]:
                        time.sleep(0.2)
                    send({"type": "partial", "seg": seg, "ch": "local", "text": text[:k], "start": start, "lang": "zh", "spk": spk})
                    time.sleep(0.25)
                send({"type": "final", "seg": seg, "ch": "local", "text": text, "start": start, "end": clock(), "lang": "zh", "spk": spk})
                i += 1
                time.sleep(1.5)

        threading.Thread(target=speak, daemon=True).start()
        try:
            while state["open"]:
                opcode, payload = read_ws_frame(self.rfile)
                if opcode is None or opcode == 0x8:
                    break
                if opcode == 0x2 and len(payload) >= 9:
                    state["offset"] = struct.unpack(">q", payload[1:9])[0]
                elif opcode == 0x1:
                    msg = json.loads(payload or b"{}")
                    if msg.get("type") == "pause":
                        state["paused"] = True
                    elif msg.get("type") == "resume":
                        state["paused"] = False
                    elif msg.get("type") == "end":
                        send({"type": "closed"})
                        break
                elif opcode == 0x9:
                    with lock:
                        self.wfile.write(bytes([0x8A, len(payload)]) + payload)
                        self.wfile.flush()
        finally:
            state["open"] = False
            self.close_connection = True


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--token", default="demo-token")
    args = parser.parse_args()
    TOKEN = args.token
    print(f"mock SCH-900S Hunyuan server on :{args.port} (token {TOKEN})")
    Server(("0.0.0.0", args.port), Handler).serve_forever()
