"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AuthService = void 0;
const common_1 = require("@nestjs/common");
const jwt_1 = require("@nestjs/jwt");
const typeorm_1 = require("@nestjs/typeorm");
const typeorm_2 = require("typeorm");
const bcrypt = __importStar(require("bcryptjs"));
const crypto_1 = require("crypto");
const users_service_1 = require("../users/users.service");
const refresh_token_entity_1 = require("./refresh-token.entity");
const user_entity_1 = require("../users/user.entity");
const admin = __importStar(require("firebase-admin"));
const OWNER_EMAIL = process.env.OWNER_EMAIL ?? '';
function getFirebaseAdmin() {
    if (admin.apps.length > 0)
        return admin.apps[0];
    return admin.initializeApp({
        credential: admin.credential.cert({
            projectId: process.env.FIREBASE_PROJECT_ID,
            clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
            privateKey: (process.env.FIREBASE_PRIVATE_KEY ?? '').replace(/\\n/g, '\n'),
        }),
    });
}
let AuthService = class AuthService {
    constructor(users, jwt, refreshRepo) {
        this.users = users;
        this.jwt = jwt;
        this.refreshRepo = refreshRepo;
    }
    async googleAuth(dto) {
        const app = getFirebaseAdmin();
        let decoded;
        try {
            decoded = await app.auth().verifyIdToken(dto.firebaseIdToken);
        }
        catch {
            throw new common_1.UnauthorizedException('Invalid Firebase ID token');
        }
        const { uid, email, name, picture } = decoded;
        if (!email)
            throw new common_1.UnauthorizedException('No email in token');
        let user = await this.users.findByFirebaseUid(uid);
        if (!user) {
            user = await this.users.findByEmail(email);
            if (user) {
                user = await this.users.update(user.id, { firebaseUid: uid });
            }
            else {
                user = await this.users.create({
                    email,
                    displayName: name,
                    avatarUrl: picture,
                    firebaseUid: uid,
                    role: email === OWNER_EMAIL ? user_entity_1.UserRole.OWNER : user_entity_1.UserRole.USER,
                });
            }
        }
        if (email === OWNER_EMAIL && user.role !== user_entity_1.UserRole.OWNER) {
            user = await this.users.update(user.id, { role: user_entity_1.UserRole.OWNER });
        }
        return this.issueTokens(user.id, user.email, user.role, dto.deviceId);
    }
    async register(dto) {
        const existing = await this.users.findByEmail(dto.email);
        if (existing)
            throw new common_1.ConflictException('Email already registered');
        const passwordHash = await bcrypt.hash(dto.password, 10);
        const user = await this.users.create({
            email: dto.email,
            displayName: dto.displayName,
            passwordHash,
            role: dto.email === OWNER_EMAIL ? user_entity_1.UserRole.OWNER : user_entity_1.UserRole.USER,
        });
        return this.issueTokens(user.id, user.email, user.role, dto['deviceId']);
    }
    async login(dto) {
        const user = await this.users.findByEmail(dto.email);
        if (!user?.passwordHash)
            throw new common_1.UnauthorizedException('Invalid credentials');
        const ok = await bcrypt.compare(dto.password, user.passwordHash);
        if (!ok)
            throw new common_1.UnauthorizedException('Invalid credentials');
        return this.issueTokens(user.id, user.email, user.role, dto.deviceId);
    }
    async refresh(rawToken) {
        const tokenHash = this.hash(rawToken);
        const stored = await this.refreshRepo.findOne({ where: { tokenHash } });
        if (!stored || stored.revokedAt || stored.expiresAt < new Date()) {
            throw new common_1.UnauthorizedException('Invalid refresh token');
        }
        stored.revokedAt = new Date();
        await this.refreshRepo.save(stored);
        const user = await this.users.findById(stored.userId);
        if (!user)
            throw new common_1.UnauthorizedException();
        return this.issueTokens(user.id, user.email, user.role, stored.deviceId);
    }
    async logout(rawToken) {
        const tokenHash = this.hash(rawToken);
        await this.refreshRepo.update({ tokenHash }, { revokedAt: new Date() });
        return { success: true };
    }
    async issueTokens(userId, email, role, deviceId) {
        const accessTtl = Number(process.env.JWT_ACCESS_TTL ?? 900);
        const refreshTtl = Number(process.env.JWT_REFRESH_TTL ?? 2_592_000);
        const accessToken = await this.jwt.signAsync({ sub: userId, email, role }, { secret: process.env.JWT_SECRET, expiresIn: accessTtl });
        const rawRefresh = (0, crypto_1.randomBytes)(48).toString('hex');
        await this.refreshRepo.save(this.refreshRepo.create({
            userId,
            deviceId,
            tokenHash: this.hash(rawRefresh),
            expiresAt: new Date(Date.now() + refreshTtl * 1000),
        }));
        return {
            accessToken,
            accessTokenExpiresIn: accessTtl,
            refreshToken: rawRefresh,
            role,
        };
    }
    hash(token) {
        return (0, crypto_1.createHash)('sha256').update(token).digest('hex');
    }
};
exports.AuthService = AuthService;
exports.AuthService = AuthService = __decorate([
    (0, common_1.Injectable)(),
    __param(2, (0, typeorm_1.InjectRepository)(refresh_token_entity_1.RefreshToken)),
    __metadata("design:paramtypes", [users_service_1.UsersService,
        jwt_1.JwtService,
        typeorm_2.Repository])
], AuthService);
//# sourceMappingURL=auth.service.js.map