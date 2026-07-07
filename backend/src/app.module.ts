import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { APP_GUARD } from '@nestjs/core';
import { DatabaseModule } from './database/database.module';
import { AuthModule } from './auth/auth.module';
import { UsersModule } from './users/users.module';
import { ReposModule } from './repos/repos.module';
import { AiModule } from './ai/ai.module';
import { SyncModule } from './sync/sync.module';
import { TerminalModule } from './terminal/terminal.module';
import { ProjectsModule } from './projects/projects.module';
import { ConnectorsModule } from './connectors/connectors.module';
import { HealthController } from './common/health.controller';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    ThrottlerModule.forRoot([{ ttl: 60_000, limit: 120 }]),
    DatabaseModule,
    AuthModule,
    UsersModule,
    ReposModule,
    AiModule,
    SyncModule,
    TerminalModule,
    ProjectsModule,
    ConnectorsModule,
  ],
  controllers: [HealthController],
  providers: [{ provide: APP_GUARD, useClass: ThrottlerGuard }],
})
export class AppModule {}
