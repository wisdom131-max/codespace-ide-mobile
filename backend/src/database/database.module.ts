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
      synchronize: !isProduction,  // auto-migrate only in dev; prod uses migrations
      autoLoadEntities: true,
      ssl: isProduction ? { rejectUnauthorized: false } : false,
    }),
  ],
})
export class DatabaseModule {}
