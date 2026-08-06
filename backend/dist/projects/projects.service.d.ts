import { Repository } from 'typeorm';
import { Project } from './project.entity';
export declare class ProjectsService {
    private readonly repo;
    constructor(repo: Repository<Project>);
    listForUser(ownerId: string): Promise<Project[]>;
    upsert(ownerId: string, data: any): Promise<Project>;
    remove(ownerId: string, id: string): Promise<{
        deleted: boolean;
    }>;
}
