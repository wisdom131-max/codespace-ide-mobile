import { SyncService } from './sync.service';
export declare class SyncController {
    private readonly sync;
    constructor(sync: SyncService);
    pull(id: string, sinceRev?: string): import("./sync.service").PullResult;
    push(id: string, body: {
        rev: number;
        changes: any[];
    }): import("./sync.service").PushResult;
}
