#!/usr/bin/env python3
"""Управляемый mock Frigate для BlackBox-тестов. Только стандартная библиотека.

Хост, порт и учётные данные приходят из окружения: значений по умолчанию нет.
"""
import base64
import hashlib
import json
import os
import re
import secrets
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


def need(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"mock: не задана переменная {name} (см. blackbox.local.env.example)")
    return value


HOST = need("BB_MOCK_HOST")
PORT = int(need("BB_MOCK_PORT"))
USER = need("BB_USERNAME")
PASSWORD = need("BB_PASSWORD")

# 1x1 WebP: хватает для latest.webp и превью карусели.
WEBP = base64.b64decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# Видео (необязательно): каталог с init.mp4 и frag-NNN.m4s из make_media.py. Без него видео-маршруты отдают 404.
MEDIA_DIR = os.environ.get("BB_MEDIA_DIR")
INIT = FRAGMENTS = None
if MEDIA_DIR and (Path(MEDIA_DIR) / "init.mp4").exists():
    INIT = (Path(MEDIA_DIR) / "init.mp4").read_bytes()
    FRAGMENTS = [p.read_bytes() for p in sorted(Path(MEDIA_DIR).glob("frag-*.m4s"))]

# Тот же поток с дорожкой AAC (необязательно): каталог из `make_media.py <dir> audio`.
AUDIO_DIR = os.environ.get("BB_MEDIA_AUDIO_DIR")
AUDIO_INIT = AUDIO_FRAGMENTS = None
if AUDIO_DIR and (Path(AUDIO_DIR) / "init.mp4").exists():
    AUDIO_INIT = (Path(AUDIO_DIR) / "init.mp4").read_bytes()
    AUDIO_FRAGMENTS = [p.read_bytes() for p in sorted(Path(AUDIO_DIR).glob("frag-*.m4s"))]
PREVIEW = (Path(MEDIA_DIR) / "preview.mp4").read_bytes() if MEDIA_DIR and (Path(MEDIA_DIR) / "preview.mp4").exists() else None
PREVIEW_SECONDS = 120  # длительность preview.mp4 (make_media.py)
VIDEO_TIMESCALE, AUDIO_TIMESCALE = 90000, 48000
# Звук потока go2rtc: none — не объявлен; sound — объявлен и приходит; silent — объявлен, но дорожки нет.
audio_mode = "none"


def tfdt_at(fragment):
    i = fragment.find(b"tfdt")
    version = fragment[i + 4]
    return i + 8, version


def shifted(fragment, ticks):
    """Копия фрагмента с базовым временем, сдвинутым на ticks (петля живого потока идёт вперёд)."""
    pos, version = tfdt_at(fragment)
    size = 8 if version == 1 else 4
    value = int.from_bytes(fragment[pos:pos + size], "big") + ticks
    return fragment[:pos] + value.to_bytes(size, "big") + fragment[pos + size:]


def shifted_tracks(fragment, ticks):
    """Как shifted, но для всех дорожек фрагмента: видео в шкале 90 кГц, звук в шкале 48 кГц."""
    out = bytearray(fragment)
    at = 0
    for scale in (VIDEO_TIMESCALE, AUDIO_TIMESCALE):
        at = fragment.find(b"tfdt", at)
        if at < 0:
            break
        pos, version = at + 8, fragment[at + 4]
        size = 8 if version == 1 else 4
        value = int.from_bytes(fragment[pos:pos + size], "big") + ticks * scale // VIDEO_TIMESCALE
        out[pos:pos + size] = value.to_bytes(size, "big")
        at += 4
    return bytes(out)


def frag_ticks():
    """Длительность одного фрагмента и всей петли в тиках дорожки."""
    pos0, v0 = tfdt_at(FRAGMENTS[0])
    pos1, v1 = tfdt_at(FRAGMENTS[1])
    size = 8 if v0 == 1 else 4
    t0 = int.from_bytes(FRAGMENTS[0][pos0:pos0 + size], "big")
    t1 = int.from_bytes(FRAGMENTS[1][pos1:pos1 + size], "big")
    return t1 - t0, (t1 - t0) * len(FRAGMENTS)


lock = threading.Lock()
sessions = set()
reviewed = set()
request_log = []
host_log = []

# Сценарий, который тест меняет через /__control/*; reset возвращает значения по умолчанию.
camera_ids = ["yard"]
ui_timezone = None          # ui.timezone конфигурации
review_start = None         # фиксированное время обзора (с); None — «5 минут назад»
failures = {}               # имя -> [код, сколько раз ещё ответить ошибкой]
holds = {}                  # имя -> Event: пока не выставлен, ответ маршрута задерживается
preview_mode = "video"      # video — превью есть; none — сервер не знает превью
activity_payload = None     # что отправить по WebSocket в ответ на onConnect
ws_clients = []             # открытые /ws: (обработчик, замок записи)
ws_connections = 0          # принято /ws с момента reset

HOLDABLE = ("preview-media", "motion")
FAILABLE = ("viewed", "preview-media", "thumb")


def camera_config(camera_id):
    return {
        "friendly_name": camera_id.capitalize(), "enabled": True, "enabled_in_config": True,
        "detect": {"width": 1280, "height": 720, "enabled": True},
        "record": {"enabled": True}, "snapshots": {"enabled": True},
        "live": {"streams": {"Main": camera_id}},
    }


def review_list():
    now = time.time()
    start = review_start if review_start is not None else now - 300
    return [{
        "id": "bb-alert-1", "camera": camera_ids[0], "severity": "alert",
        "start_time": start, "end_time": start + (20 if review_start is not None else 60),
        "has_been_reviewed": "bb-alert-1" in reviewed,
        "thumb_path": "/media/frigate/clips/review/thumb-yard-bb-alert-1.webp",
        "data": {"objects": ["person"], "zones": [], "audio": [], "sub_labels": [], "detections": ["bb-event-1"]},
    }]


def png(width, height, rgb):
    """Сплошной PNG без сторонних библиотек: превью с узнаваемым цветом (синий, которого нет в видео)."""
    import struct
    import zlib

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    row = b"\x00" + bytes(rgb) * width
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b""))


THUMB = png(64, 36, (0, 0, 255))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.wlock = threading.Lock()  # кадры WebSocket из разных потоков не должны перемешиваться

    def log_message(self, *args):  # тишина: путь и cookie не попадают в stdout
        pass

    # --- вспомогательное ---
    def send(self, code, body, ctype="application/json", headers=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode("utf-8", "replace") if length else ""

    def authorized(self):
        match = re.search(r"frigate_token=([^;]+)", self.headers.get("Cookie", ""))
        with lock:
            return bool(match) and match.group(1) in sessions

    # --- управление сценарием: вызывает тест ---
    def control(self, method, path):
        global audio_mode, camera_ids, ui_timezone, review_start, preview_mode, activity_payload, ws_connections
        parts = path[len("/__control/"):].split("/", 1)
        name, rest = parts[0], (parts[1] if len(parts) > 1 else "")
        if name == "expire":
            with lock:
                sessions.clear()
            return self.send(200, {"ok": True})
        if name == "reset":
            with lock:
                sessions.clear(); reviewed.clear(); request_log.clear(); host_log.clear()
                audio_mode = "none"; camera_ids = ["yard"]; ui_timezone = None; review_start = None
                preview_mode = "video"; activity_payload = None; ws_connections = 0
                failures.clear()
                for gate in holds.values():
                    gate.set()
                holds.clear()
            return self.send(200, {"ok": True})
        if name == "audio":
            if rest not in ("none", "sound", "silent") or (rest == "sound" and AUDIO_INIT is None):
                return self.send(400, {"message": "bad mode"})
            with lock:
                audio_mode = rest
            return self.send(200, {"ok": True})
        if name == "cameras":
            if not rest.isdigit() or not 1 <= int(rest) <= 12:
                return self.send(400, {"message": "bad count"})
            count = int(rest)
            with lock:
                camera_ids = ["yard"] if count == 1 else [f"cam{n}" for n in range(1, count + 1)]
            return self.send(200, {"cameras": camera_ids})
        if name == "timezone":
            with lock:
                ui_timezone = None if rest in ("", "none") else rest
            return self.send(200, {"ok": True})
        if name == "review":
            # Фиксированное время обзора (с эпохи): список событий не зависит от часов устройства и mock.
            with lock:
                review_start = float(rest) if rest.replace(".", "", 1).isdigit() else None
            return self.send(200, {"ok": True})
        if name == "reviewed":
            # Обзор уже просмотрен (on) или нет (off): список событий и счётчики отдают это состояние.
            if rest not in ("on", "off"):
                return self.send(400, {"message": "bad state"})
            with lock:
                if rest == "on":
                    reviewed.add("bb-alert-1")
                else:
                    reviewed.discard("bb-alert-1")
            return self.send(200, {"ok": True})
        if name == "preview":
            if rest not in ("video", "none"):
                return self.send(400, {"message": "bad mode"})
            with lock:
                preview_mode = rest
            return self.send(200, {"ok": True})
        if name == "fail":
            # /__control/fail/<имя>/<код>/<сколько раз>
            what, code, times = (rest.split("/") + ["", "", ""])[:3]
            if what not in FAILABLE or not code.isdigit() or not times.isdigit():
                return self.send(400, {"message": "bad failure"})
            with lock:
                failures[what] = [int(code), int(times)]
            return self.send(200, {"ok": True})
        if name == "hold":
            # /__control/hold/<имя>/<on|off>: ответ маршрута ждёт, пока тест не снимет задержку
            what, state = (rest.split("/") + [""])[:2]
            if what not in HOLDABLE or state not in ("on", "off"):
                return self.send(400, {"message": "bad hold"})
            with lock:
                gate = holds.setdefault(what, threading.Event())
                if state == "on":
                    gate.clear()
                else:
                    gate.set()
            return self.send(200, {"ok": True})
        if name == "activity":
            # POST: тело — готовое сообщение WebSocket, которое mock шлёт в ответ на onConnect; none — не слать
            with lock:
                activity_payload = self.body() if method == "POST" else None
            return self.send(200, {"ok": True})
        if name == "ws":
            if rest == "connections":
                with lock:
                    return self.send(200, {"count": ws_connections})
            if rest == "send" and method == "POST":
                text = self.body().encode()
                with lock:
                    clients = list(ws_clients)
                for client, wlock in clients:
                    try:
                        with wlock:
                            client.ws_send(1, text)
                    except OSError:
                        pass
                return self.send(200, {"sent": len(clients)})
        if name == "log":
            with lock:
                return self.send(200, list(request_log))
        if name == "hosts":
            # Заголовки Host запросов к API: по ним видно, на какой адрес приложение реально ходило.
            with lock:
                return self.send(200, sorted(set(host_log)))
        if name == "reset-hosts":
            with lock:
                host_log.clear()
            return self.send(200, {"ok": True})
        if name == "credentials":
            # Тестовые логин/пароль mock: тест читает их по loopback, а не из argv instrumentation.
            return self.send(200, {"user": USER, "password": PASSWORD})
        if name == "health":
            return self.send(200, {"ok": True})
        self.send(404, {"message": "unknown control"})

    def failed(self, what):
        """Если для маршрута включена ошибка, отвечает ею и сообщает, что ответ уже отправлен."""
        with lock:
            entry = failures.get(what)
            if not entry or entry[1] <= 0:
                return False
            entry[1] -= 1
            code = entry[0]
        self.send(code, {"message": "injected"})
        return True

    def held(self, what):
        with lock:
            gate = holds.get(what)
        if gate is not None:
            gate.wait(40)

    # --- маршруты ---
    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def route(self, method):
        path = urlparse(self.path).path
        if not path.startswith("/__control/"):
            with lock:
                request_log.append(f"{method} {path}")
                del request_log[:-500]
                host_log.append(self.headers.get("Host", ""))
                del host_log[:-500]

        if path.startswith("/__control/"):
            return self.control(method, path)

        if path == "/api/login" and method == "POST":
            try:
                creds = json.loads(self.body())
            except ValueError:
                creds = {}
            if creds.get("user") != USER or creds.get("password") != PASSWORD:
                return self.send(401, {"message": "denied"})
            token = secrets.token_hex(16)
            with lock:
                sessions.add(token)
            return self.send(200, {}, headers={"Set-Cookie": f"frigate_token={token}; Path=/; HttpOnly"})

        if path == "/ws":
            return self.websocket()
        if path == "/live/mse/api/ws":
            return self.mse_socket()
        if path.startswith("/vod/") and path.endswith(".m3u8") or path.startswith("/vodmedia/"):
            return self.vod(path)

        if path.startswith("/clips/review/"):
            if not self.authorized():
                return self.send(401, {})
            if self.failed("thumb"):
                return
            return self.send(200, THUMB, "image/png")
        if re.fullmatch(r"/api/[^/]+/latest\.(webp|jpg)", path):
            if not self.authorized():
                return self.send(401, {})
            return self.send(200, WEBP, "image/webp")
        if path == "/clips/preview.mp4":
            return self.preview_media()

        if path.startswith("/api/"):
            if not self.authorized():
                return self.send(401, {"message": "unauthorized"})
            return self.api(method, path)

        # Видео (MSE/HLS) mock не отдаёт: декод кадра проверяется на устройстве отдельно.
        self.send(404, {"message": "not in mock"})

    def api(self, method, path):
        now = time.time()
        if path == "/api/profile":
            return self.send(200, {"username": USER, "role": "admin", "allowed_cameras": None})
        if path == "/api/config":
            config = {"cameras": {camera: camera_config(camera) for camera in camera_ids}}
            if ui_timezone:
                config["ui"] = {"timezone": ui_timezone}
            return self.send(200, config)
        if path.startswith("/api/go2rtc/streams/"):
            medias = ["video, recvonly, H264"] + (["audio, recvonly, AAC"] if audio_mode != "none" else [])
            return self.send(200, {"producers": [{"medias": medias}]})
        if path == "/api/stats":
            return self.send(200, {"service": {"uptime": 1000}, "cameras": {camera: {"camera_fps": 5} for camera in camera_ids}})
        if path == "/api/events":
            return self.send(200, [{"id": "bb-event-1", "camera": camera_ids[0], "label": "person",
                                    "start_time": now - 300, "end_time": now - 240,
                                    "has_clip": True, "has_snapshot": True}])
        if path == "/api/review":
            with lock:
                return self.send(200, review_list())
        if path == "/api/review/summary":
            with lock:
                done = len(reviewed)
            return self.send(200, {"last24Hours": {"total_alert": 1, "reviewed_alert": min(done, 1),
                                                   "total_detection": 0, "reviewed_detection": 0}})
        if path == "/api/review/activity/motion":
            self.held("motion")
            return self.send(200, [])
        if path == "/api/recordings/unavailable":
            return self.send(200, [])
        match = re.fullmatch(r"/api/preview/([^/]+)/start/(\d+)/end/(\d+)(/frames)?", path)
        if match:
            return self.preview_list(*match.groups())
        if re.fullmatch(r"/api/[^/]+/recordings", path):
            return self.send(200, [])
        if re.fullmatch(r"/api/[^/]+/recordings/coverage", path):
            return self.send(200, {"spans": [], "timelines": {}})
        if path == "/api/reviews/viewed" and method == "POST":
            if self.failed("viewed"):
                return
            try:
                ids = json.loads(self.body()).get("ids", [])
            except ValueError:
                ids = []
            with lock:
                reviewed.update(ids)
            return self.send(200, {"success": True})
        self.send(404, {"message": "not in mock"})

    # --- видео ---
    def ws_send(self, opcode, payload):
        head = bytes([0x80 | opcode])
        n = len(payload)
        head += bytes([n]) if n < 126 else (bytes([126]) + n.to_bytes(2, "big") if n < 65536 else bytes([127]) + n.to_bytes(8, "big"))
        self.wfile.write(head + payload)
        self.wfile.flush()

    def ws_read(self):
        head = self.rfile.read(2)
        if len(head) < 2:
            return None, b""
        size = head[1] & 0x7F
        if size == 126:
            size = int.from_bytes(self.rfile.read(2), "big")
        elif size == 127:
            size = int.from_bytes(self.rfile.read(8), "big")
        mask = self.rfile.read(4) if head[1] & 0x80 else b""
        data = self.rfile.read(size)
        if mask:
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        return head[0] & 0x0F, data

    def mse_socket(self):
        """go2rtc MSE: offer -> «mse» с MIME -> init -> фрагменты в реальном времени, петля с растущим временем."""
        key = self.headers.get("Sec-WebSocket-Key")
        if not key or not self.authorized():
            return self.send(401, {})
        if INIT is None:
            return self.send(404, {"message": "no media"})
        with lock:
            sound = audio_mode == "sound"
        init, fragments = (AUDIO_INIT, AUDIO_FRAGMENTS) if sound else (INIT, FRAGMENTS)
        codecs = "avc1.640029,mp4a.40.2" if sound else "avc1.640029"
        shift = shifted_tracks if sound else shifted
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        try:
            opcode, _offer = self.ws_read()
            if opcode != 1:
                return
            self.ws_send(1, json.dumps({"type": "mse", "value": f'video/mp4; codecs="{codecs}"'}).encode())
            self.ws_send(2, init)
            step, loop = frag_ticks()
            turn = 0
            while True:
                for fragment in fragments:
                    self.ws_send(2, shift(fragment, turn * loop) if turn else fragment)
                    time.sleep(step / 90000)
                turn += 1
        except OSError:
            pass

    def preview_list(self, camera, after, before, frames):
        """Превью Frigate: список клипов камеры за диапазон. Файл один и длится PREVIEW_SECONDS, поэтому диапазон
        нарезается клипами этой длины: смещение внутри клипа всегда укладывается в файл."""
        if frames:
            return self.send(200, [])
        if preview_mode == "none" or PREVIEW is None:
            return self.send(200, [])
        cameras = camera_ids if camera == "all" else [camera]
        first, last = int(after), int(before)
        clips = []
        for c in cameras:
            moment = first
            while moment < last and len(clips) < 4000:
                clips.append({"camera": c, "src": "clips/preview.mp4", "start": moment,
                              "end": min(moment + PREVIEW_SECONDS, last)})
                moment += PREVIEW_SECONDS
        return self.send(200, clips)

    def preview_media(self):
        """Обычный MP4 с поддержкой Range: плеер перематывает превью запросами с диапазоном байт."""
        if not self.authorized():
            return self.send(401, {})
        if self.failed("preview-media"):
            return
        self.held("preview-media")
        if PREVIEW is None:
            return self.send(404, {"message": "no media"})
        size = len(PREVIEW)
        start, end, code = 0, size - 1, 200
        match = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if match and (match.group(1) or match.group(2)):
            if match.group(1):
                start = int(match.group(1))
                end = int(match.group(2)) if match.group(2) else size - 1
            else:
                start = max(0, size - int(match.group(2)))
            end = min(end, size - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            code = 206
        body = PREVIEW[start:end + 1]
        self.send_response(code)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(body)))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        self.wfile.write(body)

    def vod(self, path):
        """Архив: master -> index -> init + сегменты. Как nginx-vod, но из тех же фрагментов."""
        if not self.authorized():
            return self.send(401, {})
        if INIT is None:
            return self.send(404, {"message": "no media"})
        step, _ = frag_ticks()
        if path.endswith("master.m3u8"):
            body = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.640029\"\nindex.m3u8\n").encode()
            return self.send(200, body, "application/vnd.apple.mpegurl")
        if path.endswith("index.m3u8"):
            lines = ["#EXTM3U", "#EXT-X-VERSION:7", f"#EXT-X-TARGETDURATION:{round(step / 90000)}",
                     "#EXT-X-PLAYLIST-TYPE:VOD", '#EXT-X-MAP:URI="/vodmedia/init.mp4"']
            for number in range(len(FRAGMENTS)):
                lines += [f"#EXTINF:{step / 90000:.3f},", f"/vodmedia/frag-{number:03d}.m4s"]
            lines.append("#EXT-X-ENDLIST")
            return self.send(200, ("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl")
        name = path.rsplit("/", 1)[-1]
        if name == "init.mp4":
            return self.send(200, INIT, "video/mp4")
        match = re.fullmatch(r"frag-(\d{3})\.m4s", name)
        if match and int(match.group(1)) < len(FRAGMENTS):
            return self.send(200, FRAGMENTS[int(match.group(1))], "video/mp4")
        self.send(404, {})

    def websocket(self):
        """/ws: считает подключения, в ответ на onConnect шлёт заданную активность, принимает push из /__control/ws/send."""
        global ws_connections
        key = self.headers.get("Sec-WebSocket-Key")
        if not key or not self.authorized():
            return self.send(401, {})
        accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        entry = (self, self.wlock)
        with lock:
            ws_connections += 1
            ws_clients.append(entry)
        try:
            while True:
                opcode, data = self.ws_read()
                if opcode is None or opcode == 0x8:
                    break
                if opcode == 0x9:
                    with self.wlock:
                        self.ws_send(0xA, data)
                elif opcode == 0x1 and b"onConnect" in data:
                    with lock:
                        payload = activity_payload
                    if payload:
                        with self.wlock:
                            self.ws_send(1, payload.encode())
        except OSError:
            pass
        finally:
            with lock:
                if entry in ws_clients:
                    ws_clients.remove(entry)


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown).start())
    print("mock: готов", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
