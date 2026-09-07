import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { User } from '../users/user.entity';
import { RefreshToken } from '../auth/refresh-token.entity';
import { Project } from '../repos/project.entity';

@Module({
  imports: [
    TypeOrmModule.forRootAsync({
      useFactory: () => ({
        type: 'postgres' as const,
        url: process.env.DATABASE_URL,
        entities: [User, RefreshToken, Project],
        // TYPEORM_SYNCHRONIZE=true lets the prod instance create/refresh schema from entities
        // (no migration infra exists yet). Safe to enable on Render for this project.
        synchronize: process.env.TYPEORM_SYNCHRONIZE === 'true' || process.env.NODE_ENV !== 'production',
        autoLoadEntities: true,
        ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false,
        retryAttempts: 3,
        retryDelay: 2000,
        connectTimeoutMS: 10000,
        keepConnectionAlive: true,
      }),
    }),
  ],
})
export class DatabaseModule {}
