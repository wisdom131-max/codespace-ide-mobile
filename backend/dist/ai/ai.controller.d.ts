import { Response } from 'express';
import { AiService, ProviderId } from './ai.service';
import { McpService } from './mcp.service';
interface ChatBody {
    provider: ProviderId;
    model: string;
    messages: {
        role: string;
        content: string;
    }[];
    apiKey?: string;
    baseUrl?: string;
}
export declare class AiController {
    private readonly ai;
    private readonly mcp;
    constructor(ai: AiService, mcp: McpService);
    chat(body: ChatBody, res: Response): Promise<void>;
    getTools(): {
        tools: import("./mcp.service").McpTool[];
    };
    executeTool(body: {
        tool: string;
        params: Record<string, string>;
    }): Promise<{
        result: string;
    }>;
}
export {};
