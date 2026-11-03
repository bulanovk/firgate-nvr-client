#!/usr/bin/env python3
"""Генерирует тестовое видео для mock Frigate: H.264 High@4.1, 10 кадров/с, фрагмент = 1 с.

Нужен PyAV (`pip install av`); ставится в отдельный venv, mock остаётся на стандартной библиотеке.
Кадр содержит яркий квадрат, который двигается, и фон, который меняет оттенок от кадра к кадру:
по скриншоту видно и «декодированный кадр» (в mock-снимке latest.webp таких цветов нет), и движение.

Результат в каталоге OUT: init.mp4 и frag-NNN.m4s. Второй аргумент `audio` добавляет дорожку AAC (синус 440 Гц, 48 кГц, моно).
Без `audio` рядом ложится preview.mp4: обычный (не фрагментированный) MP4 с moov в начале, как превью Frigate для списка событий.
"""
import io
import math
import sys
from fractions import Fraction
from pathlib import Path

import av

# 1280x720: предел зума приложения считается от ширины источника (3 × источник / экран), на 320 px зума нет.
WIDTH, HEIGHT, FPS, SECONDS = 1280, 720, 10, 20
AUDIO_RATE = 48000
SQUARE = (255, 0, 255)  # magenta: узнаваемый цвет, которого нет в интерфейсе приложения


def frame_array(index):
    import numpy as np
    shade = 40 + (index * 3) % 80
    image = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    image[:, :, 1] = shade  # зелёноватый фон, оттенок меняется
    size = 160
    x = (index * 28) % (WIDTH - size)
    y = (index * 12) % (HEIGHT - size)
    image[y:y + size, x:x + size] = SQUARE
    return image


def boxes(data):
    pos = 0
    while pos < len(data):
        size = int.from_bytes(data[pos:pos + 4], "big")
        kind = data[pos + 4:pos + 8]
        yield kind, data[pos:pos + size]
        pos += size


def audio_array(first, count):
    import numpy as np
    t = (np.arange(count) + first) / AUDIO_RATE
    return (0.3 * np.sin(2 * math.pi * 440 * t)).astype(np.float32).reshape(1, -1)


PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_FPS, PREVIEW_SECONDS = 640, 360, 10, 120


def preview_array(index):
    import numpy as np
    image = np.zeros((PREVIEW_HEIGHT, PREVIEW_WIDTH, 3), dtype=np.uint8)
    image[:, :, 1] = 40 + (index * 3) % 80
    size = 80
    x = (index * 14) % (PREVIEW_WIDTH - size)
    y = (index * 6) % (PREVIEW_HEIGHT - size)
    image[y:y + size, x:x + size] = SQUARE
    return image


def write_preview(out):
    """Превью событий: обычный MP4 (moov в начале, ключевой кадр каждую секунду), чтобы плеер мог перематывать по Range."""
    container = av.open(str(out / "preview.mp4"), mode="w", format="mp4", options={"movflags": "+faststart"})
    stream = container.add_stream("libx264", rate=PREVIEW_FPS)
    stream.width, stream.height, stream.pix_fmt = PREVIEW_WIDTH, PREVIEW_HEIGHT, "yuv420p"
    stream.options = {"profile": "high", "g": str(PREVIEW_FPS), "keyint_min": str(PREVIEW_FPS),
                      "sc_threshold": "0", "bf": "0", "preset": "veryfast"}
    for index in range(PREVIEW_FPS * PREVIEW_SECONDS):
        for packet in stream.encode(av.VideoFrame.from_ndarray(preview_array(index), format="rgb24")):
            container.mux(packet)
    for packet in stream.encode():
        container.mux(packet)
    container.close()


def main(out, with_audio=False):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    buffer = io.BytesIO()
    container = av.open(buffer, mode="w", format="mp4",
                        options={"movflags": "frag_keyframe+empty_moov+default_base_moof",
                                 "video_track_timescale": "90000"})
    stream = container.add_stream("libx264", rate=FPS)
    stream.width, stream.height, stream.pix_fmt = WIDTH, HEIGHT, "yuv420p"
    stream.time_base = Fraction(1, 90000)
    stream.options = {"profile": "high", "level": "4.1", "g": str(FPS), "keyint_min": str(FPS),
                      "sc_threshold": "0", "bf": "0", "preset": "veryfast"}
    sound = None
    sent = 0
    if with_audio:
        sound = container.add_stream("aac", rate=AUDIO_RATE)
        sound.layout = "mono"
    for index in range(FPS * SECONDS):
        frame = av.VideoFrame.from_ndarray(frame_array(index), format="rgb24")
        frame.pts = index * (90000 // FPS)
        frame.time_base = Fraction(1, 90000)
        for packet in stream.encode(frame):
            container.mux(packet)
        if sound is not None:
            # Звук идёт вровень с видео: столько отсчётов, сколько прошло к концу этого кадра.
            until = (index + 1) * AUDIO_RATE // FPS
            while sent < until:
                count = min(1024, until - sent)
                chunk = av.AudioFrame.from_ndarray(audio_array(sent, count), format="fltp", layout="mono")
                chunk.sample_rate = AUDIO_RATE
                chunk.pts = sent
                chunk.time_base = Fraction(1, AUDIO_RATE)
                for packet in sound.encode(chunk):
                    container.mux(packet)
                sent += count
    for packet in stream.encode():
        container.mux(packet)
    if sound is not None:
        for packet in sound.encode():
            container.mux(packet)
    container.close()

    init = bytearray()
    fragments = []
    current = bytearray()
    for kind, box in boxes(buffer.getvalue()):
        if kind in (b"ftyp", b"moov"):
            init += box
        elif kind in (b"moof", b"mdat"):
            current += box
            if kind == b"mdat":
                fragments.append(bytes(current))
                current = bytearray()
    (out / "init.mp4").write_bytes(bytes(init))
    for number, fragment in enumerate(fragments):
        (out / f"frag-{number:03d}.m4s").write_bytes(fragment)
    if not with_audio:
        write_preview(out)
    print(f"media: init + {len(fragments)} фрагментов в {out}")


if __name__ == "__main__":
    main(sys.argv[1], len(sys.argv) > 2 and sys.argv[2] == "audio")
