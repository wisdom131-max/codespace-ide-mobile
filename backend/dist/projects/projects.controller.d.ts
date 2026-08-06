import { ProjectsService } from './projects.service';
export declare class ProjectsController {
    private readonly projects;
    constructor(projects: ProjectsService);
    list(req: any): Promise<import("./project.entity").Project[]>;
    upsert(req: any, body: any): Promise<import("./project.entity").Project>;
    update(req: any, id: string, body: any): Promise<import("./project.entity").Project>;
    remove(req: any, id: string): Promise<{
        deleted: boolean;
    }>;
}
