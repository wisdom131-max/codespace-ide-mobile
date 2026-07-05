import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { User } from '../users/user.entity';
import { RefreshToken } from '../auth/refresh-token.entity';
import { Project } from '../repos/project.entity';

@Module({
  imports: [
    TypeOrmModule.forRoot({
      type: 'postgres',
      url: process.env.DATABASE_URL,
      entities: [User, RefreshToken, Project],
      synchronize: process.env.NODE_ENV !== 'production',  // only sync in dev
      autoLoadEntities: true,
      ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false,
      retryAttempts: 10,
      retryDelay: 3000,
      connectTimeoutMS: 10000,
    }),
  ],
})
export class DatabaseModule {}
