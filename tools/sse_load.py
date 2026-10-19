"""Нагрузка для проверки нативной сборки: N одновременных потоков SSE к /v1/chat/completions.

ML_API_KEY=… python3 tools/sse_load.py --url http://127.0.0.1:8080 --streams 20   (ключ — из переменной, не из аргументов)
Пока потоки идут, смотрите память процесса: ps -o rss= -p $(pgrep ml-server)  (или docker stats).
"""
import argparse
import os
import json
import threading
import time
import urllib.request


def stream(url, key, results, index):
    body = json.dumps({"model": "tpu-assistant", "stream": True,
                       "messages": [{"role": "user", "content": f"поток {index}: расскажи подробно"}]}).encode()
    request = urllib.request.Request(url + "/v1/chat/completions", data=body, method="POST", headers={
        "Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            events = sum(1 for line in response if line.startswith(b"data:"))
            results[index] = (response.status, events, time.monotonic() - started)
    except urllib.error.HTTPError as e:
        results[index] = (e.code, 0, time.monotonic() - started)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--key", default=os.environ.get("ML_API_KEY"), help="по умолчанию — переменная ML_API_KEY")
    parser.add_argument("--streams", type=int, default=20)
    args = parser.parse_args()
    if not args.key:
        parser.error("нужен ключ: переменная ML_API_KEY или --key")
    results = {}
    threads = [threading.Thread(target=stream, args=(args.url, args.key, results, i)) for i in range(args.streams)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    codes = sorted(r[0] for r in results.values())
    print(f"потоков: {len(results)}, коды: {codes}")
    print(f"событий в потоке: мин {min(r[1] for r in results.values())}, макс {max(r[1] for r in results.values())}")
    print(f"длительность: макс {max(r[2] for r in results.values()):.1f} с")


if __name__ == "__main__":
    main()
