import { BadRequestException, Injectable, NotFoundException, UnauthorizedException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { JwtService } from '@nestjs/jwt';
import { ConnectorToken } from './connector-token.entity';
import { CONNECTORS } from './connector-registry';
import { encrypt, decrypt } from './crypto.util';

const CALLBACK_PATH = '/api/v1/connectors/callback';
const STATE_SECRET = () => process.env.JWT_SECRET || 'dev_secret';

function publicBaseUrl(): string {
  return process.env.PUBLIC_BASE_URL
    || (process.env.RAILWAY_PUBLIC_DOMAIN ? `https://${process.env.RAILWAY_PUBLIC_DOMAIN}` : 'http://localhost:8080');
}

@Injectable()
export class ConnectorsService {
  constructor(
    @InjectRepository(ConnectorToken) private readonly repo: Repository<ConnectorToken>,
    private readonly jwt: JwtService,
  ) {}

  async statusForUser(ownerId: string) {
    const rows = await this.repo.find({ where: { ownerId } });
    const byService = new Map(rows.map((r) => [r.service, r]));
    return Object.values(CONNECTORS).map((c) => ({
      id: c.id,
      name: c.name,
      connected: byService.has(c.id),
      configured: !!process.env[c.clientIdEnv],
      scope: byService.get(c.id)?.scope ?? null,
    }));
  }

  getAuthUrl(ownerId: string, service: string): string {
    const conn = CONNECTORS[service];
    if (!conn) throw new BadRequestException(`Unknown connector: ${service}`);
    const clientId = process.env[conn.clientIdEnv];
    if (!clientId) {
      throw new BadRequestException(
        `${conn.name} isn't configured on the server yet (missing ${conn.clientIdEnv}). Ask the owner to add it.`,
      );
    }

    const state = this.jwt.sign(
      { ownerId, service, nonce: Math.random().toString(36).slice(2) },
      { secret: STATE_SECRET(), expiresIn: '10m' },
    );

    const params = new URLSearchParams({
      client_id: clientId,
      redirect_uri: `${publicBaseUrl()}${CALLBACK_PATH}`,
      response_type: 'code',
      scope: conn.defaultScope,
      state,
      ...(conn.extraAuthParams ?? {}),
    });
    return `${conn.authUrl}?${params.toString()}`;
  }

  async handleCallback(code?: string, state?: string, error?: string): Promise<{ ok: boolean; message: string }> {
    if (error) return { ok: false, message: `Authorization was denied (${error}).` };
    if (!code || !state) return { ok: false, message: 'Missing authorization code — please try connecting again from the app.' };

    let payload: any;
    try {
      payload = this.jwt.verify(state, { secret: STATE_SECRET() });
    } catch {
      return { ok: false, message: 'This link expired or is invalid. Please try connecting again from the app.' };
    }
    const { ownerId, service } = payload;
    const conn = CONNECTORS[service];
    if (!conn) return { ok: false, message: `Unknown connector: ${service}` };

    const clientId = process.env[conn.clientIdEnv];
    const clientSecret = process.env[conn.clientSecretEnv];
    if (!clientId || !clientSecret) {
      return { ok: false, message: `${conn.name} isn't fully configured on the server.` };
    }

    const body = new URLSearchParams({
      client_id: clientId,
      client_secret: clientSecret,
      code,
      grant_type: 'authorization_code',
      redirect_uri: `${publicBaseUrl()}${CALLBACK_PATH}`,
    });

    const resp = await fetch(conn.tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body,
    });
    const json: any = await resp.json().catch(() => ({}));

    // Slack's "Sign in with Slack" nests the user token under authed_user; normalize both shapes.
    const accessToken = json.access_token ?? json.authed_user?.access_token;
    const refreshToken = json.refresh_token ?? null;
    const expiresIn = json.expires_in ?? null;
    const scope = json.scope ?? conn.defaultScope;

    if (!resp.ok || !accessToken) {
      return {
        ok: false,
        message: json.error_description || json.error || `Failed to connect ${conn.name} (HTTP ${resp.status}).`,
      };
    }

    const existing = await this.repo.findOne({ where: { ownerId, service } });
    const entity: Partial<ConnectorToken> = {
      ownerId,
      service,
      accessTokenEnc: encrypt(accessToken),
      refreshTokenEnc: refreshToken ? encrypt(refreshToken) : existing?.refreshTokenEnc,
      expiresAt: expiresIn ? new Date(Date.now() + expiresIn * 1000) : undefined,
      scope,
    };
    if (existing) {
      await this.repo.update({ id: existing.id }, entity);
    } else {
      await this.repo.save(this.repo.create(entity));
    }
    return { ok: true, message: `${conn.name} connected! You can close this tab and go back to the app.` };
  }

  private async getValidAccessToken(ownerId: string, service: string): Promise<string> {
    const conn = CONNECTORS[service];
    if (!conn) throw new BadRequestException(`Unknown connector: ${service}`);
    const row = await this.repo.findOne({ where: { ownerId, service } });
    if (!row) throw new NotFoundException(`${conn.name} is not connected. Connect it first.`);

    const isExpired = row.expiresAt && row.expiresAt.getTime() < Date.now() + 60_000;
    if (!isExpired) return decrypt(row.accessTokenEnc);
    if (!row.refreshTokenEnc) return decrypt(row.accessTokenEnc); // no refresh token available — best effort

    const clientId = process.env[conn.clientIdEnv];
    const clientSecret = process.env[conn.clientSecretEnv];
    const body = new URLSearchParams({
      client_id: clientId!,
      client_secret: clientSecret!,
      refresh_token: decrypt(row.refreshTokenEnc),
      grant_type: 'refresh_token',
    });
    const resp = await fetch(conn.tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
      body,
    });
    const json: any = await resp.json().catch(() => ({}));
    if (!resp.ok || !json.access_token) {
      throw new UnauthorizedException(`${conn.name} session expired and couldn't refresh. Reconnect it.`);
    }
    await this.repo.update(
      { id: row.id },
      {
        accessTokenEnc: encrypt(json.access_token),
        expiresAt: json.expires_in ? new Date(Date.now() + json.expires_in * 1000) : undefined,
      },
    );
    return json.access_token;
  }

  async proxyCall(ownerId: string, service: string, method: string, path: string, body?: any) {
    const conn = CONNECTORS[service];
    if (!conn) throw new BadRequestException(`Unknown connector: ${service}`);
    const token = await this.getValidAccessToken(ownerId, service);

    const url = path.startsWith('http') ? path : `${conn.apiBase}${path.startsWith('/') ? '' : '/'}${path}`;
    const resp = await fetch(url, {
      method: method.toUpperCase(),
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await resp.text();
    let data: any;
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
    return { status: resp.status, data };
  }

  async disconnect(ownerId: string, service: string) {
    const conn = CONNECTORS[service];
    if (!conn) throw new BadRequestException(`Unknown connector: ${service}`);
    const row = await this.repo.findOne({ where: { ownerId, service } });
    if (!row) return { disconnected: false };

    if (conn.revokeUrl) {
      try {
        await fetch(conn.revokeUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ token: decrypt(row.accessTokenEnc) }),
        });
      } catch {
        /* best-effort revoke — ignore failures */
      }
    }
    await this.repo.delete({ id: row.id });
    return { disconnected: true };
  }
}
