import { User } from '../users/user.entity';
export declare class ConnectorToken {
    id: string;
    owner: User;
    ownerId: string;
    service: string;
    accessTokenEnc: string;
    refreshTokenEnc?: string;
    expiresAt?: Date;
    scope?: string;
    createdAt: Date;
    updatedAt: Date;
}
