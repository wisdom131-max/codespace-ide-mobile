export declare enum UserRole {
    OWNER = "owner",
    USER = "user"
}
export declare class User {
    id: string;
    email: string;
    displayName?: string;
    avatarUrl?: string;
    passwordHash?: string;
    firebaseUid?: string;
    role: UserRole;
    isActive: boolean;
    createdAt: Date;
    updatedAt: Date;
}
