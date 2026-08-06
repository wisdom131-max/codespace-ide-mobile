export declare class GithubService {
    private client;
    listRepos(token: string): Promise<{
        id: number;
        name: string;
        fullName: string;
        defaultBranch: string;
        private: boolean;
    }[]>;
    createPullRequest(token: string, owner: string, repo: string, body: {
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
    listCodespaces(token: string): Promise<{
        name: string;
        state: "Unknown" | "Created" | "Queued" | "Provisioning" | "Available" | "Awaiting" | "Unavailable" | "Deleted" | "Moved" | "Shutdown" | "Archived" | "Starting" | "ShuttingDown" | "Failed" | "Exporting" | "Updating" | "Rebuilding";
        repo: string;
    }[]>;
}
