"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConnectorsService = void 0;
const common_1 = require("@nestjs/common");
const typeorm_1 = require("@nestjs/typeorm");
const typeorm_2 = require("typeorm");
const jwt_1 = require("@nestjs/jwt");
const connector_token_entity_1 = require("./connector-token.entity");
const connector_registry_1 = require("./connector-registry");
const crypto_util_1 = require("./crypto.util");
const CALLBACK_PATH = '/api/v1/connectors/callback';
const STATE_SECRET = () => process.env.JWT_SECRET || 'dev_secret';
function publicBaseUrl() {
    return process.env.PUBLIC_BASE_URL
        || (process.env.RAILWAY_PUBLIC_DOMAIN ? `https://${process.env.RAILWAY_PUBLIC_DOMAIN}` : 'http://localhost:8080');
}
let ConnectorsService = class ConnectorsService {
    constructor(repo, jwt) {
        this.repo = repo;
        this.jwt = jwt;
    }
    async statusForUser(ownerId) {
        const rows = await this.repo.find({ where: { ownerId } });
        const byService = new Map(rows.map((r) => [r.service, r]));
        return Object.values(connector_registry_1.CONNECTORS).map((c) => ({
            id: c.id,
            name: c.name,
            connected: byService.has(c.id),
            configured: !!process.env[c.clientIdEnv],
            scope: byService.get(c.id)?.scope ?? null,
        }));
    }
    getAuthUrl(ownerId, service) {
        const conn = connector_registry_1.CONNECTORS[service];
        if (!conn)
            throw new common_1.BadRequestException(`Unknown connector: ${service}`);
        const clientId = process.env[conn.clientIdEnv];
        if (!clientId) {
            throw new common_1.BadRequestException(`${conn.name} isn't configured on the server yet (missing ${conn.clientIdEnv}). Ask the owner to add it.`);
        }
        const state = this.jwt.sign({ ownerId, service, nonce: Math.random().toString(36).slice(2) }, { secret: STATE_SECRET(), expiresIn: '10m' });
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
    async handleCallback(code, state, error) {
        if (error)
            return { ok: false, message: `Authorization was denied (${error}).` };
        if (!code || !state)
            return { ok: false, message: 'Missing authorization code — please try connecting again from the app.' };
        let payload;
        try {
            payload = this.jwt.verify(state, { secret: STATE_SECRET() });
        }
        catch {
            return { ok: false, message: 'This link expired or is invalid. Please try connecting again from the app.' };
        }
        const { ownerId, service } = payload;
        const conn = connector_registry_1.CONNECTORS[service];
        if (!conn)
            return { ok: false, message: `Unknown connector: ${service}` };
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
        const json = await resp.json().catch(() => ({}));
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
        const entity = {
            ownerId,
            service,
            accessTokenEnc: (0, crypto_util_1.encrypt)(accessToken),
            refreshTokenEnc: refreshToken ? (0, crypto_util_1.encrypt)(refreshToken) : existing?.refreshTokenEnc,
            expiresAt: expiresIn ? new Date(Date.now() + expiresIn * 1000) : undefined,
            scope,
        };
        if (existing) {
            await this.repo.update({ id: existing.id }, entity);
        }
        else {
            await this.repo.save(this.repo.create(entity));
        }
        return { ok: true, message: `${conn.name} connected! You can close this tab and go back to the app.` };
    }
    async getAccessTokenForService(ownerId, service) {
        return this.getValidAccessToken(ownerId, service);
    }
    async getValidAccessToken(ownerId, service) {
        const conn = connector_registry_1.CONNECTORS[service];
        if (!conn)
            throw new common_1.BadRequestException(`Unknown connector: ${service}`);
        const row = await this.repo.findOne({ where: { ownerId, service } });
        if (!row)
            throw new common_1.NotFoundException(`${conn.name} is not connected. Connect it first.`);
        const isExpired = row.expiresAt && row.expiresAt.getTime() < Date.now() + 60_000;
        if (!isExpired)
            return (0, crypto_util_1.decrypt)(row.accessTokenEnc);
        if (!row.refreshTokenEnc)
            return (0, crypto_util_1.decrypt)(row.accessTokenEnc);
        const clientId = process.env[conn.clientIdEnv];
        const clientSecret = process.env[conn.clientSecretEnv];
        const body = new URLSearchParams({
            client_id: clientId,
            client_secret: clientSecret,
            refresh_token: (0, crypto_util_1.decrypt)(row.refreshTokenEnc),
            grant_type: 'refresh_token',
        });
        const resp = await fetch(conn.tokenUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
            body,
        });
        const json = await resp.json().catch(() => ({}));
        if (!resp.ok || !json.access_token) {
            throw new common_1.UnauthorizedException(`${conn.name} session expired and couldn't refresh. Reconnect it.`);
        }
        await this.repo.update({ id: row.id }, {
            accessTokenEnc: (0, crypto_util_1.encrypt)(json.access_token),
            expiresAt: json.expires_in ? new Date(Date.now() + json.expires_in * 1000) : undefined,
        });
        return json.access_token;
    }
    async proxyCall(ownerId, service, method, path, body) {
        const conn = connector_registry_1.CONNECTORS[service];
        if (!conn)
            throw new common_1.BadRequestException(`Unknown connector: ${service}`);
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
        let data;
        try {
            data = JSON.parse(text);
        }
        catch {
            data = text;
        }
        return { status: resp.status, data };
    }
    async disconnect(ownerId, service) {
        const conn = connector_registry_1.CONNECTORS[service];
        if (!conn)
            throw new common_1.BadRequestException(`Unknown connector: ${service}`);
        const row = await this.repo.findOne({ where: { ownerId, service } });
        if (!row)
            return { disconnected: false };
        if (conn.revokeUrl) {
            try {
                await fetch(conn.revokeUrl, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: new URLSearchParams({ token: (0, crypto_util_1.decrypt)(row.accessTokenEnc) }),
                });
            }
            catch {
            }
        }
        await this.repo.delete({ id: row.id });
        return { disconnected: true };
    }
};
exports.ConnectorsService = ConnectorsService;
exports.ConnectorsService = ConnectorsService = __decorate([
    (0, common_1.Injectable)(),
    __param(0, (0, typeorm_1.InjectRepository)(connector_token_entity_1.ConnectorToken)),
    __metadata("design:paramtypes", [typeorm_2.Repository,
        jwt_1.JwtService])
], ConnectorsService);
//# sourceMappingURL=connectors.service.js.map