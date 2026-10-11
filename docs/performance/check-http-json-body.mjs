import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { acceptEncoding, decodeHttpBody } from './http-json-body.mjs';

const source = Buffer.from(JSON.stringify({ code: 200, data: { nickname: '测试玩家', scores: [6,-2,-2,-2] } }));
const packed = gzipSync(source);
assert.deepEqual(JSON.parse(decodeHttpBody(packed, 'gzip').text), JSON.parse(source));
assert.equal(decodeHttpBody(packed, 'gzip').decodedBytes, source.length);
assert.equal(decodeHttpBody(source).contentEncoding, 'identity');
assert.equal(decodeHttpBody(source, 'identity').text, source.toString());
assert.throws(() => decodeHttpBody(Buffer.from('broken'), 'gzip'));
assert.throws(() => decodeHttpBody(gzipSync(Buffer.alloc(100_000)), 'gzip', 1000));
assert.throws(() => decodeHttpBody(source, 'br'), /unsupported/);
assert.throws(() => decodeHttpBody(source, '', 4), /too-large/);
assert.equal(acceptEncoding(), 'identity');
assert.equal(acceptEncoding('gzip'), 'gzip');
assert.throws(() => acceptEncoding('br'));
console.log('HTTP 压测解码通过：identity/gzip 数据一致、中文、损坏响应和解压大小上限。');
