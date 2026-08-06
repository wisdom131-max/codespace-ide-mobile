import { GithubService } from './github.service';
import { ConnectorsService } from '../connectors/connectors.service';
export declare class ReposController {
    private readonly github;
    private readonly connectors;
    constructor(github: GithubService, connectors: ConnectorsService);
    private tokenFor;
    repos(req: any): Promise<{
        id: number;
        name: string;
        fullName: string;
        defaultBranch: string;
        private: boolean;
    }[]>;
    createPr(req: any, owner: string, repo: string, body: {
        title: string;
        head: string;
        base: string;
        body?: string;
    }): Promise<{
        number: number;
        title: string;
        state: "open" | "closed";
        url: string;
    }>;
    codespaces(req: any): Promise<{
        name: string;
        state: "Unknown" | "Created" | "Queued" | "Provisioning" | "Available" | "Awaiting" | "Unavailable" | "Deleted" | "Moved" | "Shutdown" | "Archived" | "Starting" | "ShuttingDown" | "Failed" | "Exporting" | "Updating" | "Rebuilding";
        repo: string;
    }[]>;
}
