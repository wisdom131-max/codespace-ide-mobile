import { Body, Controller, Delete, Get, Param, Post, Query, Req, Res, UseGuards } from '@nestjs/common';
import type { Response } from 'express';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { ConnectorsService } from './connectors.service';

@ApiTags('connectors')
@Controller('connectors')
export class ConnectorsController {
  constructor(private readonly connectors: ConnectorsService) {}

  @Get()
  @ApiBearerAuth()
  @UseGuards(JwtAuthGuard)
  status(@Req() req: any) {
    return this.connectors.statusForUser(req.user.userId);
  }

  @Get(':service/auth-url')
  @ApiBearerAuth()
  @UseGuards(JwtAuthGuard)
  authUrl(@Req() req: any, @Param('service') service: string) {
    return { authUrl: this.connectors.getAuthUrl(req.user.userId, service) };
  }

  /**
   * Public — hit directly by the OAuth provider's browser redirect, so no Bearer token is
   * available here. Identity + CSRF protection comes from the signed `state` JWT instead
   * (minted in getAuthUrl, verified here). Do NOT put this behind JwtAuthGuard.
   */
  @Get('callback')
  async callback(
    @Query('code') code: string,
    @Query('state') state: string,
    @Query('error') error: string,
    @Res() res: Response,
  ) {
    const result = await this.connectors.handleCallback(code, state, error);
    res
      .status(200)
      .type('html')
      .send(`<!DOCTYPE html><html><head><title>CodeSpace IDE</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:-apple-system,system-ui,sans-serif;display:flex;align-items:center;
        justify-content:center;height:100vh;margin:0;background:#111;color:#eee;text-align:center}
        div{max-width:360px;padding:24px}</style></head>
        <body><div><h2>${result.ok ? '✅ Connected' : '⚠️ Something went wrong'}</h2>
        <p>${result.message}</p></div></body></html>`);
  }

  @Post(':service/call')
  @ApiBearerAuth()
  @UseGuards(JwtAuthGuard)
  call(
    @Req() req: any,
    @Param('service') service: string,
    @Body() body: { method: string; path: string; body?: any },
  ) {
    return this.connectors.proxyCall(req.user.userId, service, body.method, body.path, body.body);
  }

  @Delete(':service')
  @ApiBearerAuth()
  @UseGuards(JwtAuthGuard)
  disconnect(@Req() req: any, @Param('service') service: string) {
    return this.connectors.disconnect(req.user.userId, service);
  }
}
