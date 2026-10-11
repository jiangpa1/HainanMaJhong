"""Isolated real Nginx test; synthetic V2 fixtures, no DB or production reload.
Run on Linux: python3 verify-gzip.py --output report.json --requests 200
Only listens on loopback; subprocess and temporary files are removed on exit.
"""
import argparse, concurrent.futures, gzip, hashlib, http.client, json, os
import pathlib, statistics, subprocess, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def fixtures():
    def envelope(data):
        return json.dumps(dict(code=200, message="成功", data=data), ensure_ascii=False,
                          separators=(",", ":")).encode()
    players = [dict(userId=7 if s == 0 else -s, seat=s, nickname="测试玩家" + str(s),
                    totalScore=6000 if s == 0 else -2000, rank=1 if s == 0 else 2,
                    winCount=1000 if s == 0 else 0, highestFanValue=2, highestFan="测试番型")
               for s in range(4)]
    def rounds(count):
        return [dict(roundId=n, roundNum=n, draw=False, winType=0, winnerId=7, loserId=None,
                     totalFan=2, dealerSeat="EAST", windIdx=0,
                     participants=[dict(roundId=n, userId=p["userId"], seat=p["seat"],
                                        nickname=p["nickname"], scoreChange=6 if p["seat"] == 0 else -2,
                                        winner=p["seat"] == 0, me=p["seat"] == 0) for p in players])
                for n in range(1, count + 1)]
    overview = dict(sessionId=42, roomId="123456", status=0, readThroughRoundNum=1000,
                    latestRoundNum=1000, version="42:1000", players=players,
                    totalScore=6000, rank=1, winCount=1000, highestFanValue=2, highestFan="测试番型")
    page = dict(sessionId=42, readThroughRoundNum=1000, version="42:1000", rounds=rounds(10),
                hasMore=True, nextAfterRoundNum=10, pageSize=10)
    detail = dict(roundId=1, roundNum=1, winHand=dict(hand=list(range(14)), melds=[]),
                  fanInfo={"测试番型": 2}, scores=[dict(**p, detail={"notes": ["分数明细"] * 8,
                  "flowers": [34, 35], "gangs": [1, 2]}) for p in players])
    return {"/api/v2/records": envelope(dict(records=[dict(sessionId=42, roomId="123456",
            totalScore=6000, status=0)] , total=1, pages=1)),
            "/api/v2/records/42/overview": envelope(overview),
            "/api/v2/records/42/rounds": envelope(page),
            "/api/v2/records/42/rounds/1": envelope(detail),
            "/api/records/42": envelope(dict(overview=overview, rounds=rounds(1000))),
            "/api/v2/records/small": envelope([]),
            "/api/v2/records/small-chunked": envelope([]),
            "/api/login": envelope(dict(example="x" * 3000))}


def get(port, route, encoding, via=False):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    started = time.perf_counter()
    try:
        headers = {"Accept-Encoding": encoding, "Authorization": "Bearer synthetic-test-only"}
        if via:
            headers["Via"] = "1.1 synthetic-proxy"
        conn.request("GET", route, headers=headers)
        res = conn.getresponse()
        body = res.read(2_000_001)
        if len(body) > 2_000_000:
            raise AssertionError("Response exceeded test limit")
        return dict(status=res.status, headers=dict(res.getheaders()), body=body,
                    ms=(time.perf_counter() - started) * 1000)
    finally:
        conn.close()


def counter():
    return {parts[0].rstrip(":"): int(parts[9]) for line in pathlib.Path("/proc/net/dev").read_text().splitlines()[2:]
            if len(parts := line.split()) >= 10}


def cpu(pid):
    # comm can contain spaces: parse fields after the final closing parenthesis.
    fields = pathlib.Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")


def percentile(values, q):
    values = sorted(values)
    return values[max(0, __import__('math').ceil(len(values) * q) - 1)]


def run(args):
    data = fixtures()
    class Upstream(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def do_GET(self):
            route = self.path.split("?", 1)[0]
            body = data.get(route, b'{"code":404}')
            self.send_response(200 if route in data else 404)
            self.send_header("Content-Type", "application/json")
            chunked = route.endswith('/small-chunked')
            if chunked:
                self.send_header("Transfer-Encoding", "chunked")
            else:
                self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "private, no-store")
            self.end_headers()
            self.wfile.write((f'{len(body):x}\r\n'.encode() + body + b'\r\n0\r\n\r\n') if chunked else body)
        def log_message(self, *args):
            pass
    upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    processes = []
    report = dict(at=__import__('datetime').datetime.now(__import__('datetime').timezone.utc).isoformat(),
                  fixture="synthetic V2 schema and legacy long history; not production data",
                  nginx=subprocess.check_output([args.nginx, "-v"], stderr=subprocess.STDOUT, text=True).strip(),
                  requestsPerStage=args.requests, concurrency=4, levels=[], assertions=[])
    try:
        with tempfile.TemporaryDirectory(prefix="mahjong-gzip-") as temp:
            root = pathlib.Path(temp)
            (root / "logs").mkdir()
            location = pathlib.Path(__file__).with_name("records-gzip-location.conf").read_text()
            logformat = pathlib.Path(__file__).with_name("records-gzip-log.conf").read_text()
            for level in (0, 1, 2, 3):
                # Ask OS for an available loopback port, then launch a private Nginx master.
                import socket
                with socket.socket() as s:
                    s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]
                stage = root / str(level); stage.mkdir()
                loc = location.replace("127.0.0.1:8080", f"127.0.0.1:{upstream.server_port}")
                loc = loc.replace("/var/log/nginx/records-gzip.log", str(stage / "access.log"))
                loc = loc.replace("gzip_comp_level 1;", f"gzip_comp_level {max(1, level)};")
                if level == 0:
                    loc = loc.replace("gzip on;", "gzip off;")
                config = stage / "nginx.conf"
                config.write_text(f"pid {stage}/nginx.pid; error_log {stage}/error.log;\n"
                    "worker_processes 1; events { worker_connections 128; }\nhttp {\n" + logformat +
                    f"\nserver {{ listen 127.0.0.1:{port};\n" + loc +
                    f"\nlocation / {{ gzip off; proxy_pass http://127.0.0.1:{upstream.server_port}; }}\n}}\n}}")
                subprocess.run([args.nginx, "-t", "-p", str(root) + "/", "-c", str(config)],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                proc = subprocess.Popen([args.nginx, "-p", str(root) + "/", "-c", str(config),
                                         "-g", "daemon off;"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                processes.append(proc)
                for _ in range(50):
                    try:
                        get(port, "/api/v2/records/small", "identity"); break
                    except OSError:
                        time.sleep(.05)
                else:
                    raise RuntimeError("Isolated Nginx did not start")
                rows = []
                for route, original in data.items():
                    plain = get(port, route, "identity")
                    compressed = get(port, route, "gzip", via=True)
                    encoding = compressed["headers"].get("Content-Encoding", "identity")
                    decoded = gzip.decompress(compressed["body"]) if encoding == "gzip" else compressed["body"]
                    assert plain["status"] == compressed["status"] == 200
                    assert plain["body"] == decoded == original, route
                    assert "Content-Encoding" not in plain["headers"], route
                    targeted = route.startswith("/api/v2/records") or route.startswith("/api/records")
                    should_compress = level > 0 and targeted and (len(original) >= 1024 or route.endswith('/small-chunked'))
                    assert (encoding == "gzip") == should_compress, route
                    if targeted:
                        assert compressed["headers"]["Cache-Control"] == "private, no-store"
                        if should_compress:
                            assert compressed["headers"].get("Vary", "").lower() == "accept-encoding", route
                    if should_compress:
                        denied = get(port, route, "gzip;q=0, identity")
                        assert "Content-Encoding" not in denied["headers"], route
                    rows.append(dict(route=route, originalBytes=len(original), bodyBytes=len(compressed["body"]),
                                     savedPercent=round(100 * (1 - len(compressed["body"]) / len(original)), 2),
                                     encoding=encoding, decodedSha256=hashlib.sha256(decoded).hexdigest()))
                children = pathlib.Path(f"/proc/{proc.pid}/task/{proc.pid}/children").read_text().split()
                workers = [int(child) for child in children]
                assert workers, "Nginx worker missing"
                encoding = "gzip" if level else "identity"
                # Warm up; assess the bounded first page, not only a huge legacy response.
                for _ in range(10): get(port, "/api/v2/records/42/rounds", encoding)
                net_start, cpu_start = counter(), sum(cpu(p) for p in workers)
                start = time.perf_counter()
                with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                    results = list(pool.map(lambda _: get(port, "/api/v2/records/42/rounds", encoding), range(args.requests)))
                elapsed = time.perf_counter() - start
                cpu_seconds = sum(cpu(p) for p in workers) - cpu_start
                net_end = counter()
                assert all(r["status"] == 200 for r in results)
                times = [r["ms"] for r in results]
                # Flush logs by gracefully stopping ONLY this private master.
                proc.send_signal(__import__('signal').SIGQUIT); proc.wait(timeout=10)
                records = [json.loads(line) for line in (stage / "access.log").read_text().splitlines()]
                report["levels"].append(dict(level=level, cases=rows, elapsedSeconds=round(elapsed, 3),
                    p50Ms=round(percentile(times, .5), 3), p95Ms=round(percentile(times, .95), 3),
                    p99Ms=round(percentile(times, .99), 3), nginxWorkerCpuSeconds=round(cpu_seconds, 3),
                    nginxWorkerCpuCorePercent=round(100 * cpu_seconds / elapsed, 2),
                    sampleBodyBytes=sum(len(r["body"]) for r in results),
                    interfaceTxBytes={k: net_end[k] - v for k, v in net_start.items() if k in net_end},
                    accessLogBodyBytes=sum(r["bodyBytes"] for r in records),
                    accessLogHttpBytes=sum(r["httpBytes"] for r in records)))
                print(f"level {level}: p95={report['levels'][-1]['p95Ms']}ms; CPU={cpu_seconds:.3f}s", flush=True)
    finally:
        for proc in processes:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
        upstream.shutdown(); upstream.server_close()
    report["assertions"] = ["identity and gunzip bytes identical", "gzip;q=0 respected",
        "Vary Accept-Encoding", "1024 byte Content-Length threshold and unknown-length chunked exception", "Via + Authorization",
        "private/no-store and no shared cache", "login route excluded"]
    report["measurementLimits"] = ["Synthetic fixtures; cannot predict real account compression ratio",
        "Loopback proxy and synthetic Python upstream; latency includes fixture server cost",
        "NIC tx counters are host totals including unrelated traffic; lo includes upstream hop",
        "access log body/HTTP bytes include warmup and functional probes; sampleBodyBytes is load stage only",
        "CPU resolution is Linux clock tick; small differences are not statistically conclusive",
        "gzip_min_length uses Content-Length; unknown-length chunked bodies may still compress below threshold"]
    pathlib.Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("PASS: all decoding, encoding negotiation and cache assertions")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--nginx", default="/usr/sbin/nginx")
    parser.add_argument("--output", default="gzip-results.json")
    parser.add_argument("--requests", type=int, default=200)
    args = parser.parse_args()
    if not 20 <= args.requests <= 2000:
        parser.error("requests must be 20..2000")
    run(args)
