export declare class Project {
    id: string;
    userId: string;
    name: string;
    kind: string;
    gitRemoteUrl?: string;
    defaultBranch?: string;
    remoteConfig: Record<string, unknown>;
    createdAt: Date;
}
