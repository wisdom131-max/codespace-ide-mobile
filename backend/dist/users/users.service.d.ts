import { Repository } from 'typeorm';
import { User } from './user.entity';
export declare class UsersService {
    private readonly repo;
    constructor(repo: Repository<User>);
    findByEmail(email: string): Promise<User | null>;
    findById(id: string): Promise<User | null>;
    findByFirebaseUid(firebaseUid: string): Promise<User | null>;
    create(data: Partial<User>): Promise<User>;
    update(id: string, data: Partial<User>): Promise<{
        id: string;
        email?: string | undefined;
        displayName?: string | undefined;
        avatarUrl?: string | undefined;
        passwordHash?: string | undefined;
        firebaseUid?: string | undefined;
        role?: import("./user.entity").UserRole | undefined;
        isActive?: boolean | undefined;
        createdAt?: Date | undefined;
        updatedAt?: Date | undefined;
    } & User>;
}
