import { Body, Controller, Post, Res, UseGuards } from '@nestjs/common';
import { Response } from 'express';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { AiService, ProviderId } from './ai.service';
import { McpService, MCP_TOOLS } from './mcp.service';

interface ChatBody {
  provider: ProviderId;
  model: string;
  messages: { role: string; content: string }[];
  apiKey?: string;
  baseUrl?: string;
}

@ApiTags('ai')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('ai')
export class AiController {
  constructor(private readonly ai: AiService, private readonly mcp: McpService) {}

  @Post('chat')
  async chat(@Body() body: ChatBody, @Res() res: Response) {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();
    try {
      for await (const frame of this.ai.chatStream(body)) {
        res.write(frame);
      }
    } catch (e: any) {
      res.write(`event: error\ndata: ${JSON.stringify({ message: e?.message })}\n\n`);
    } finally {
      res.end();
    }
  }

  @Post('mcp/tools')
  getTools() {
    return { tools: MCP_TOOLS };
  }

  @Post('mcp/execute')
  async executeTool(@Body() body: { tool: string; params: Record<string, string> }) {
    const result = await this.mcp.executeTool(body.tool, body.params);
    return { result };
  }
}
