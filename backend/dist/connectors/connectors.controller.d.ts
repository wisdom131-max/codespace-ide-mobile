import type { Response } from 'express';
import { ConnectorsService } from './connectors.service';
export declare class ConnectorsController {
    private readonly connectors;
    constructor(connectors: ConnectorsService);
    status(req: any): Promise<{
        id: string;
        name: string;
        connected: boolean;
        configured: boolean;
        scope: string | null;
    }[]>;
    authUrl(req: any, service: string): {
        authUrl: string;
    };
    callback(code: string, state: string, error: string, res: Response): Promise<void>;
    call(req: any, service: string, body: {
        method: string;
        path: string;
        body?: any;
    }): Promise<{
        status: number;
        data: any;
    }>;
    disconnect(req: any, service: string): Promise<{
        disconnected: boolean;
    }>;
}
