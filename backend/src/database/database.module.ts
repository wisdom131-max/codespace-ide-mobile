import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { User } from '../users/user.entity';
import { RefreshToken } from '../auth/refresh-token.entity';
import { Project } from '../repos/project.entity';

const isProduction = process.env.NODE_ENV === 'production';

@Module({
  imports: [
    TypeOrmModule.forRoot({
      type: 'postgres',
      url: process.env.DATABASE_URL,
      entities: [User, RefreshToken, Project],
      synchronize: !isProduction,
      autoLoadEntities: true,
      ssl: isProduction ? { rejectUnauthorized: false } : false,
      // Retry on startup so Railway healthcheck has time to pass
      // before TypeORM gives up (DB may take a few seconds to become ready)
      retryAttempts: 10,
      retryDelay: 3000,
      connectTimeoutMS: 10000,
    }),
  ],
})
export class DatabaseModule {}
