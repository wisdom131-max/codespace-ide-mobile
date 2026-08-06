export interface Change {
    path: string;
    op: 'write' | 'delete' | 'rename';
    checksum?: string;
}
export interface PullResult {
    rev: number;
    changes: Change[];
    hasMore: boolean;
    sinceRev: number;
}
export interface PushResult {
    accepted: boolean;
    conflicts?: string[];
    serverRev?: number;
    rev?: number;
    applied?: number;
}
export declare class SyncService {
    private revs;
    pull(projectId: string, sinceRev: number): PullResult;
    push(projectId: string, clientRev: number, changes: Change[]): PushResult;
}
