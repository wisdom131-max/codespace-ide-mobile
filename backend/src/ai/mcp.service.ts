import { Injectable } from '@nestjs/common';
import * as fs from 'fs';
import * as path from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';

const execAsync = promisify(exec);

export interface McpTool {
  name: string;
  description: string;
  parameters: Record<string, string>;
}

export const MCP_TOOLS: McpTool[] = [
  { name: 'read_file', description: 'Read the contents of a file', parameters: { path: 'string' } },
  { name: 'write_file', description: 'Write content to a file', parameters: { path: 'string', content: 'string' } },
  { name: 'list_dir', description: 'List files in a directory', parameters: { path: 'string' } },
  { name: 'search_files', description: 'Search for text in files', parameters: { query: 'string', dir: 'string' } },
  { name: 'run_command', description: 'Run a terminal command', parameters: { command: 'string' } },
];

@Injectable()
export class McpService {
  private workspaceRoot = process.env.WORKSPACE_ROOT ?? '/workspaces';

  async executeTool(name: string, params: Record<string, string>): Promise<string> {
    try {
      switch (name) {
        case 'read_file': {
          const filePath = path.resolve(this.workspaceRoot, params.path);
          if (!filePath.startsWith(this.workspaceRoot)) return 'Error: path outside workspace';
          return fs.readFileSync(filePath, 'utf-8');
        }
        case 'write_file': {
          const filePath = path.resolve(this.workspaceRoot, params.path);
          if (!filePath.startsWith(this.workspaceRoot)) return 'Error: path outside workspace';
          fs.mkdirSync(path.dirname(filePath), { recursive: true });
          fs.writeFileSync(filePath, params.content, 'utf-8');
          return 'File written successfully';
        }
        case 'list_dir': {
          const dirPath = path.resolve(this.workspaceRoot, params.path ?? '');
          if (!dirPath.startsWith(this.workspaceRoot)) return 'Error: path outside workspace';
          const entries = fs.readdirSync(dirPath, { withFileTypes: true });
          return entries.map(e => `${e.isDirectory() ? '[DIR]' : '[FILE]'} ${e.name}`).join('\n');
        }
        case 'search_files': {
          const dirPath = path.resolve(this.workspaceRoot, params.dir ?? '');
          if (!dirPath.startsWith(this.workspaceRoot)) return 'Error: path outside workspace';
          const { stdout } = await execAsync(`grep -rl "${params.query}" "${dirPath}" 2>/dev/null | head -20`);
          return stdout || 'No matches found';
        }
        case 'run_command': {
          const { stdout, stderr } = await execAsync(params.command, {
            cwd: this.workspaceRoot,
            timeout: 30000,
          });
          return stdout || stderr || 'Command completed';
        }
        default:
          return `Unknown tool: ${name}`;
      }
    } catch (e: any) {
      return `Error: ${e.message}`;
    }
  }
}
