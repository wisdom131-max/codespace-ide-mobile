import { JwtService } from '@nestjs/jwt';
import { Repository } from 'typeorm';
import { UsersService } from '../users/users.service';
import { RefreshToken } from './refresh-token.entity';
import { LoginDto, RegisterDto, GoogleAuthDto } from './dto';
import { UserRole } from '../users/user.entity';
export declare class AuthService {
    private readonly users;
    private readonly jwt;
    private readonly refreshRepo;
    constructor(users: UsersService, jwt: JwtService, refreshRepo: Repository<RefreshToken>);
    googleAuth(dto: GoogleAuthDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: UserRole;
    }>;
    register(dto: RegisterDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: UserRole;
    }>;
    login(dto: LoginDto): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: UserRole;
    }>;
    refresh(rawToken: string): Promise<{
        accessToken: string;
        accessTokenExpiresIn: number;
        refreshToken: string;
        role: UserRole;
    }>;
    logout(rawToken: string): Promise<{
        success: boolean;
    }>;
    private issueTokens;
    private hash;
}
