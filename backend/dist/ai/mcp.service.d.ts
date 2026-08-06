export interface McpTool {
    name: string;
    description: string;
    parameters: Record<string, string>;
}
export declare const MCP_TOOLS: McpTool[];
export declare class McpService {
    private workspaceRoot;
    executeTool(name: string, params: Record<string, string>): Promise<string>;
}
