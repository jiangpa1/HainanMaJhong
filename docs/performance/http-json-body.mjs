import { gunzipSync } from 'node:zlib';

export function decodeHttpBody(body, contentEncoding = '', limit = 2_000_000) {
  const encoding = String(contentEncoding).trim().toLowerCase();
  if (body.length > limit) throw new Error('response-too-large');
  if (encoding && encoding !== 'identity' && encoding !== 'gzip') {
    throw new Error('unsupported-content-encoding');
  }
  const decoded = encoding === 'gzip' ? gunzipSync(body, { maxOutputLength: limit }) : body;
  if (decoded.length > limit) throw new Error('decoded-response-too-large');
  return { text: decoded.toString('utf8'), decodedBytes: decoded.length,
    contentEncoding: encoding || 'identity' };
}

export function acceptEncoding(value = 'identity') {
  if (!['identity', 'gzip'].includes(value)) {
    throw new Error('LOAD_TEST_ACCEPT_ENCODING must be identity or gzip');
  }
  return value;
}
