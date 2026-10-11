import assert from 'node:assert/strict'
import { GameSocket } from '../src/game/socket.js'
globalThis.location = { protocol: 'http:', host: 'localhost' }
globalThis.localStorage = { getItem: () => null }
class FakeSocket {
  static OPEN = 1
  static CONNECTING = 0
  readyState = 1
  sent = []
  constructor() { FakeSocket.latest = this }
  send(frame) { this.sent.push(JSON.parse(frame)) }
}
globalThis.WebSocket = FakeSocket
let received = false
const game = new GameSocket({ path: '/game', autoReconnect: false, onMessage: () => { received = true } })
game.connect()
const ws = FakeSocket.latest
ws.onmessage({ data: JSON.stringify({ type: 'end', _monitorSeq: 7 }) })
assert.equal(received, true)
assert.deepEqual(ws.sent, [{ type: 'metricsAck', seq: 7 }])
ws.onmessage({ data: JSON.stringify({ type: 'end', _monitorSeq: '7' }) })
ws.onmessage({ data: JSON.stringify({ type: 'end' }) })
assert.equal(ws.sent.length, 1)
const room = new GameSocket({ path: '/room', autoReconnect: false })
room.connect()
FakeSocket.latest.onmessage({ data: JSON.stringify({ _monitorSeq: 8 }) })
assert.equal(FakeSocket.latest.sent.length, 0)
console.log('Monitor ACK checks passed')
