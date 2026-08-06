import { Repository } from 'typeorm';
import { JwtService } from '@nestjs/jwt';
import { ConnectorToken } from './connector-token.entity';
export declare class ConnectorsService {
    private readonly repo;
    private readonly jwt;
    constructor(repo: Repository<ConnectorToken>, jwt: JwtService);
    statusForUser(ownerId: string): Promise<{
        id: string;
        name: string;
        connected: boolean;
        configured: boolean;
        scope: string | null;
    }[]>;
    getAuthUrl(ownerId: string, service: string): string;
    handleCallback(code?: string, state?: string, error?: string): Promise<{
        ok: boolean;
        message: string;
    }>;
    getAccessTokenForService(ownerId: string, service: string): Promise<string>;
    private getValidAccessToken;
    proxyCall(ownerId: string, service: string, method: string, path: string, body?: any): Promise<{
        status: number;
        data: any;
    }>;
    disconnect(ownerId: string, service: string): Promise<{
        disconnected: boolean;
    }>;
}
