import { Module } from '@nestjs/common';
import { AiService } from './ai.service';
import { McpService } from './mcp.service';
import { AiController } from './ai.controller';

@Module({
  providers: [AiService, McpService],
  controllers: [AiController],
})
export class AiModule {}
