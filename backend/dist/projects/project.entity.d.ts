import { User } from '../users/user.entity';
export declare enum ProjectKind {
    LOCAL = "LOCAL",
    GIT = "GIT",
    CONTAINER = "CONTAINER"
}
export declare class Project {
    id: string;
    name: string;
    kind: ProjectKind;
    pathOrUrl: string;
    defaultBranch?: string;
    owner: User;
    ownerId: string;
    createdAt: Date;
    updatedAt: Date;
}
