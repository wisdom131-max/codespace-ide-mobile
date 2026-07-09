import { Controller, Get, Post, Body, Param, UseGuards, Request } from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { GithubService } from './github.service';
import { ConnectorsService } from '../connectors/connectors.service';

@ApiTags('github')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('github')
export class ReposController {
  constructor(
    private readonly github: GithubService,
    private readonly connectors: ConnectorsService,
  ) {}

  // Resolves the user's stored GitHub token via the same encrypted connector_tokens store
  // used by Gmail/Calendar/Drive/Slack (see connectors module). Throws NotFoundException
  // (404, human-readable message) if the user hasn't connected GitHub yet — the app should
  // catch that and prompt "Connect GitHub" same as it does for the other connectors.
  private tokenFor(req: any): Promise<string> {
    return this.connectors.getAccessTokenForService(req.user.userId, 'github');
  }

  @Get('repos')
  async repos(@Request() req: any) {
    return this.github.listRepos(await this.tokenFor(req));
  }

  @Post('repos/:owner/:repo/pulls')
  async createPr(
    @Request() req: any,
    @Param('owner') owner: string,
    @Param('repo') repo: string,
    @Body() body: { title: string; head: string; base: string; body?: string },
  ) {
    return this.github.createPullRequest(await this.tokenFor(req), owner, repo, body);
  }

  @Get('codespaces')
  async codespaces(@Request() req: any) {
    return this.github.listCodespaces(await this.tokenFor(req));
  }
}
