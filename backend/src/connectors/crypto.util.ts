import { createCipheriv, createDecipheriv, randomBytes, createHash } from 'crypto';

/**
 * AES-256-GCM at-rest encryption for OAuth access/refresh tokens stored in Postgres.
 * Uses CONNECTOR_ENCRYPTION_KEY (any string, hashed to 32 bytes) if set on Railway.
 * Falls back to a key derived from JWT_SECRET so the service never fails to boot without
 * it — but set CONNECTOR_ENCRYPTION_KEY separately in production for real key separation.
 */
function getKey(): Buffer {
  const raw = process.env.CONNECTOR_ENCRYPTION_KEY || process.env.JWT_SECRET || 'dev_insecure_key';
  return createHash('sha256').update(raw).digest(); // 32 bytes, exactly what aes-256-gcm needs
}

export function encrypt(plain: string): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', getKey(), iv);
  const enc = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, enc]).toString('base64');
}

export function decrypt(payload: string): string {
  const buf = Buffer.from(payload, 'base64');
  const iv = buf.subarray(0, 12);
  const tag = buf.subarray(12, 28);
  const enc = buf.subarray(28);
  const decipher = createDecipheriv('aes-256-gcm', getKey(), iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(enc), decipher.final()]).toString('utf8');
}
