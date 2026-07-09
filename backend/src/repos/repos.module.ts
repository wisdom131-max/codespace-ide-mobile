import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { Project } from './project.entity';
import { GithubService } from './github.service';
import { ReposController } from './repos.controller';
import { ConnectorsModule } from '../connectors/connectors.module';

@Module({
  imports: [TypeOrmModule.forFeature([Project]), ConnectorsModule],
  providers: [GithubService],
  controllers: [ReposController],
})
export class ReposModule {}
