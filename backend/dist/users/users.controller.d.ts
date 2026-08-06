import { UsersService } from './users.service';
export declare class UsersController {
    private readonly users;
    constructor(users: UsersService);
    me(req: any): Promise<{
        id: string | undefined;
        email: string | undefined;
        displayName: string | undefined;
    }>;
}
