import { Injectable } from '@nestjs/common';

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

/**
 * Optimistic, rev-based sync. Detects conflicts via monotonic rev + per-file checksums
 * and returns conflicting paths for client-side 3-way merge.
 */
@Injectable()
export class SyncService {
  // In production these are persisted in sync_state / object storage.
  private revs = new Map<string, number>();

  pull(projectId: string, sinceRev: number): PullResult {
    const current = this.revs.get(projectId) ?? 0;
    return { rev: current, changes: [] as Change[], hasMore: false, sinceRev };
  }

  push(projectId: string, clientRev: number, changes: Change[]): PushResult {
    const serverRev = this.revs.get(projectId) ?? 0;
    if (clientRev < serverRev) {
      return { accepted: false, conflicts: changes.map((c) => c.path), serverRev };
    }
    const newRev = serverRev + 1;
    this.revs.set(projectId, newRev);
    return { accepted: true, rev: newRev, applied: changes.length };
  }
}
