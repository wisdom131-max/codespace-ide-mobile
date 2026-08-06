export declare class RegisterDto {
    email: string;
    password: string;
    displayName?: string;
}
export declare class LoginDto {
    email: string;
    password: string;
    deviceId?: string;
}
export declare class RefreshDto {
    refreshToken: string;
}
export declare class GoogleAuthDto {
    firebaseIdToken: string;
    deviceId?: string;
}
