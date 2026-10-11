"""Run through SSH stdin on the application host. Creates one isolated smoke account.
No credentials, tokens, hidden tiles or environment values are emitted.
"""
import base64, hashlib, json, os, secrets, socket, struct, time, urllib.request, zipfile

def api(path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('http://127.0.0.1:8080' + path,
        data=json.dumps(body).encode() if body is not None else None, headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)

class WS:
    def __init__(self, path, token):
        self.sock = socket.create_connection(('127.0.0.1', 8080), timeout=10)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        self.sock.sendall((f'GET {path} HTTP/1.1\r\nHost: localhost:8080\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {token}\r\n\r\n').encode())
        headers = b''
        while not headers.endswith(b'\r\n\r\n'): headers += self.exact(1)
        assert b' 101 ' in headers.split(b'\r\n')[0], 'WebSocket handshake failed'
    def exact(self, n):
        data = b''
        while len(data) < n:
            part = self.sock.recv(n-len(data))
            if not part: raise RuntimeError('WebSocket closed')
            data += part
        return data
    def send(self, msg):
        raw = json.dumps(msg).encode(); mask = secrets.token_bytes(4)
        length = len(raw)
        header = bytes([0x81, 0x80 | length]) if length < 126 else bytes([0x81, 0xfe]) + struct.pack('!H', length)
        self.sock.sendall(header + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(raw)))
    def until(self, kind):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            first, length = self.exact(2); length &= 127
            if length == 126: length = struct.unpack('!H', self.exact(2))[0]
            elif length == 127: length = struct.unpack('!Q', self.exact(8))[0]
            assert length < 2_000_000, 'Unexpected frame size'
            raw = self.exact(length)
            if first & 15 == 8: raise RuntimeError('WebSocket closed')
            if first & 15 != 1: continue
            msg = json.loads(raw)
            if msg.get('type') == kind: return msg
        raise RuntimeError('Expected event not received: ' + kind)
    def close(self): self.sock.close()

with zipfile.ZipFile('/root/mahjong/HainanMaJhong.jar') as jar:
    checks = {'repository/RoomSnapshotRepository.class': [b'room:lifecycle:', b'claimRestore'],
              'room/Room.class': [b'generation'], 'room/State.class': [b'CLOSED'],
              'service/impl/RoundPersister.class': [b'lockSession']}
    deployed = {name: all(value in jar.read('BOOT-INF/classes/hainanMahjong/' + name) for value in values)
                for name, values in checks.items()}
assert all(deployed.values()), 'Deployed lifecycle classes differ'
credentials = {'username': 'ops_' + secrets.token_hex(6), 'password': secrets.token_urlsafe(24), 'nickname': '生命周期验证'}
registered = api('/api/register', credentials); assert registered['code'] == 200, 'Register failed'
user_id = registered['data']['id']
logged = api('/api/login', credentials); assert logged['code'] == 200, 'Login failed'
token = logged['data']['accessToken']
report = {'deployedClasses': deployed, 'testUserId': user_id, 'rooms': []}
try:
    for playing in [False, True]:
        room_ws = WS('/room', token); game_ws = None
        try:
            room_ws.send({'type': 'createRoom'})
            room = room_ws.until('room_info'); code = room['code']
            seat = next(m['seat'] for m in room['members'] if m['userId'] == user_id)
            if playing:
                room_ws.send({'type': 'fillBots'})
                room_ws.until('room_info')
                room_ws.send({'type': 'startNow'})
                room_ws.until('start')
                game_ws = WS('/game?code=' + code + '&seat=' + seat, token)
                game_ws.until('hand_start')
            room_ws.send({'type': 'leaveRoom'})
            room_ws.until('room_closed')
            time.sleep(1)
            pending = api('/api/room/pending', token=token)
            check = api('/api/room/check?code=' + code, token=token)
            room_ws.send({'type': 'joinRoom', 'code': code})
            denied = room_ws.until('join_denied')
            assert pending['data']['exists'] is False, 'Closed room remains returnable'
            assert check['code'] != 200, 'Closed room join check accepted'
            report['rooms'].append({'code': code, 'started': playing, 'pendingCleared': True,
                'joinRejected': bool(denied), 'checkRejected': True})
        finally:
            if game_ws: game_ws.close()
            room_ws.close()
finally:
    api('/api/logout', {}, token)
print(json.dumps(report, ensure_ascii=False))
