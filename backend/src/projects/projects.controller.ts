import {
  Body, Controller, Delete, Get,
  Param, Post, Put, Req, UseGuards,
} from '@nestjs/common';
import { ApiBearerAuth, ApiTags } from '@nestjs/swagger';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { ProjectsService } from './projects.service';

@ApiTags('projects')
@ApiBearerAuth()
@UseGuards(JwtAuthGuard)
@Controller('projects')
export class ProjectsController {
  constructor(private readonly projects: ProjectsService) {}

  @Get()
  list(@Req() req: any) {
    return this.projects.listForUser(req.user.userId);
  }

  @Post()
  upsert(@Req() req: any, @Body() body: any) {
    return this.projects.upsert(req.user.userId, body);
  }

  @Put(':id')
  update(@Req() req: any, @Param('id') id: string, @Body() body: any) {
    return this.projects.upsert(req.user.userId, { ...body, id });
  }

  @Delete(':id')
  remove(@Req() req: any, @Param('id') id: string) {
    return this.projects.remove(req.user.userId, id);
  }
}
