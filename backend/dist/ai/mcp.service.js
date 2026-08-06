"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.McpService = exports.MCP_TOOLS = void 0;
const common_1 = require("@nestjs/common");
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const child_process_1 = require("child_process");
const util_1 = require("util");
const execAsync = (0, util_1.promisify)(child_process_1.exec);
exports.MCP_TOOLS = [
    { name: 'read_file', description: 'Read the contents of a file', parameters: { path: 'string' } },
    { name: 'write_file', description: 'Write content to a file', parameters: { path: 'string', content: 'string' } },
    { name: 'list_dir', description: 'List files in a directory', parameters: { path: 'string' } },
    { name: 'search_files', description: 'Search for text in files', parameters: { query: 'string', dir: 'string' } },
    { name: 'run_command', description: 'Run a terminal command', parameters: { command: 'string' } },
];
let McpService = class McpService {
    constructor() {
        this.workspaceRoot = process.env.WORKSPACE_ROOT ?? '/workspaces';
    }
    async executeTool(name, params) {
        try {
            switch (name) {
                case 'read_file': {
                    const filePath = path.resolve(this.workspaceRoot, params.path);
                    if (!filePath.startsWith(this.workspaceRoot))
                        return 'Error: path outside workspace';
                    return fs.readFileSync(filePath, 'utf-8');
                }
                case 'write_file': {
                    const filePath = path.resolve(this.workspaceRoot, params.path);
                    if (!filePath.startsWith(this.workspaceRoot))
                        return 'Error: path outside workspace';
                    fs.mkdirSync(path.dirname(filePath), { recursive: true });
                    fs.writeFileSync(filePath, params.content, 'utf-8');
                    return 'File written successfully';
                }
                case 'list_dir': {
                    const dirPath = path.resolve(this.workspaceRoot, params.path ?? '');
                    if (!dirPath.startsWith(this.workspaceRoot))
                        return 'Error: path outside workspace';
                    const entries = fs.readdirSync(dirPath, { withFileTypes: true });
                    return entries.map(e => `${e.isDirectory() ? '[DIR]' : '[FILE]'} ${e.name}`).join('\n');
                }
                case 'search_files': {
                    const dirPath = path.resolve(this.workspaceRoot, params.dir ?? '');
                    if (!dirPath.startsWith(this.workspaceRoot))
                        return 'Error: path outside workspace';
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
        }
        catch (e) {
            return `Error: ${e.message}`;
        }
    }
};
exports.McpService = McpService;
exports.McpService = McpService = __decorate([
    (0, common_1.Injectable)()
], McpService);
//# sourceMappingURL=mcp.service.js.map