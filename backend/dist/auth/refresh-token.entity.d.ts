export declare class RefreshToken {
    id: string;
    userId: string;
    tokenHash: string;
    deviceId?: string;
    expiresAt: Date;
    revokedAt?: Date;
    createdAt: Date;
}
