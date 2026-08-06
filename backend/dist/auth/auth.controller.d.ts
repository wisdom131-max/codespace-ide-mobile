import { AuthService } from './auth.service';
import { LoginDto, RefreshDto, RegisterDto, GoogleAuthDto } from './dto';
export declare class AuthController {
    private readonly auth;
    constructor(auth: AuthService);
    googleAuth(dto: GoogleAuthDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: import("../users/user.entity").UserRole;
    }>;
    register(dto: RegisterDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: import("../users/user.entity").UserRole;
    }>;
    login(dto: LoginDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: import("../users/user.entity").UserRole;
    }>;
    refresh(dto: RefreshDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: import("../users/user.entity").UserRole;
    }>;
    logout(dto: RefreshDto): Promise<{
        success: boolean;
    }>;
}
