export interface ConnectorDef {
    id: string;
    name: string;
    authUrl: string;
    tokenUrl: string;
    revokeUrl?: string;
    defaultScope: string;
    apiBase: string;
    clientIdEnv: string;
    clientSecretEnv: string;
    extraAuthParams?: Record<string, string>;
}
export declare const CONNECTORS: Record<string, ConnectorDef>;
