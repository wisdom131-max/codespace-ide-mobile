import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Project, ProjectKind } from './project.entity';

@Injectable()
export class ProjectsService {
  constructor(
    @InjectRepository(Project)
    private readonly repo: Repository<Project>,
  ) {}

  listForUser(ownerId: string): Promise<Project[]> {
    return this.repo.find({
      where: { ownerId },
      order: { updatedAt: 'DESC' },
    });
  }

  async upsert(ownerId: string, data: any): Promise<Project> {
    const existing = data.id
      ? await this.repo.findOne({ where: { id: data.id, ownerId } })
      : null;

    const entity: Partial<Project> = {
      id:            data.id ?? String(Date.now()),
      name:          data.name,
      kind:          data.kind ?? ProjectKind.LOCAL,
      pathOrUrl:     data.pathOrUrl ?? 'local',
      defaultBranch: data.defaultBranch ?? 'main',
      ownerId,
    };

    if (existing) {
      await this.repo.update({ id: entity.id!, ownerId }, entity);
      return this.repo.findOne({ where: { id: entity.id!, ownerId } }) as Promise<Project>;
    }
    return this.repo.save(this.repo.create(entity));
  }

  async remove(ownerId: string, id: string): Promise<{ deleted: boolean }> {
    const result = await this.repo.delete({ id, ownerId });
    if (!result.affected) throw new NotFoundException('Project not found');
    return { deleted: true };
  }
}
