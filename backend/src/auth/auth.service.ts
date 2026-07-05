import { Injectable, UnauthorizedException, ConflictException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import * as bcrypt from 'bcryptjs';
import { createHash, randomBytes } from 'crypto';
import { UsersService } from '../users/users.service';
import { RefreshToken } from './refresh-token.entity';
import { LoginDto, RegisterDto, GoogleAuthDto } from './dto';
import { UserRole } from '../users/user.entity';
import * as admin from 'firebase-admin';

// The ONE email that gets owner privileges — yours
const OWNER_EMAIL = process.env.OWNER_EMAIL ?? '';

// Lazy-init Firebase Admin (safe to call multiple times)
function getFirebaseAdmin(): admin.app.App {
  if (admin.apps.length > 0) return admin.apps[0]!;
  return admin.initializeApp({
    credential: admin.credential.cert({
      projectId:   process.env.FIREBASE_PROJECT_ID,
      clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
      privateKey:  (process.env.FIREBASE_PRIVATE_KEY ?? '').replace(/\\n/g, '\n'),
    }),
  });
}

@Injectable()
export class AuthService {
  constructor(
    private readonly users: UsersService,
    private readonly jwt: JwtService,
    @InjectRepository(RefreshToken)
    private readonly refreshRepo: Repository<RefreshToken>,
  ) {}

  // ── Google Sign-In ──────────────────────────────────────────────────────────
  async googleAuth(dto: GoogleAuthDto) {
    const app = getFirebaseAdmin();

    // Verify the Firebase ID token
    let decoded: admin.auth.DecodedIdToken;
    try {
      decoded = await app.auth().verifyIdToken(dto.firebaseIdToken);
    } catch {
      throw new UnauthorizedException('Invalid Firebase ID token');
    }

    const { uid, email, name, picture } = decoded;
    if (!email) throw new UnauthorizedException('No email in token');

    // Find or create user
    let user = await this.users.findByFirebaseUid(uid);
    if (!user) {
      user = await this.users.findByEmail(email);
      if (user) {
        // Link existing account to Firebase UID
        user = await this.users.update(user.id, { firebaseUid: uid });
      } else {
        // Brand-new user
        user = await this.users.create({
          email,
          displayName: name,
          avatarUrl:   picture,
          firebaseUid: uid,
          role: email === OWNER_EMAIL ? UserRole.OWNER : UserRole.USER,
        });
      }
    }

    // Promote to owner if the email matches — handles case where owner
    // signed in before OWNER_EMAIL env var was set
    if (email === OWNER_EMAIL && user.role !== UserRole.OWNER) {
      user = await this.users.update(user.id, { role: UserRole.OWNER });
    }

    return this.issueTokens(user.id, user.email, user.role, dto.deviceId);
  }

  // ── Email / Password ────────────────────────────────────────────────────────
  async register(dto: RegisterDto) {
    const existing = await this.users.findByEmail(dto.email);
    if (existing) throw new ConflictException('Email already registered');
    const passwordHash = await bcrypt.hash(dto.password, 10);
    const user = await this.users.create({
      email: dto.email,
      displayName: dto.displayName,
      passwordHash,
      role: dto.email === OWNER_EMAIL ? UserRole.OWNER : UserRole.USER,
    });
    return this.issueTokens(user.id, user.email, user.role, dto['deviceId']);
  }

  async login(dto: LoginDto) {
    const user = await this.users.findByEmail(dto.email);
    if (!user?.passwordHash) throw new UnauthorizedException('Invalid credentials');
    const ok = await bcrypt.compare(dto.password, user.passwordHash);
    if (!ok) throw new UnauthorizedException('Invalid credentials');
    return this.issueTokens(user.id, user.email, user.role, dto.deviceId);
  }

  async refresh(rawToken: string) {
    const tokenHash = this.hash(rawToken);
    const stored = await this.refreshRepo.findOne({ where: { tokenHash } });
    if (!stored || stored.revokedAt || stored.expiresAt < new Date()) {
      throw new UnauthorizedException('Invalid refresh token');
    }
    stored.revokedAt = new Date();
    await this.refreshRepo.save(stored);
    const user = await this.users.findById(stored.userId);
    if (!user) throw new UnauthorizedException();
    return this.issueTokens(user.id, user.email, user.role, stored.deviceId);
  }

  async logout(rawToken: string) {
    const tokenHash = this.hash(rawToken);
    await this.refreshRepo.update({ tokenHash }, { revokedAt: new Date() });
    return { success: true };
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────
  private async issueTokens(userId: string, email: string, role: UserRole, deviceId?: string) {
    const accessTtl  = Number(process.env.JWT_ACCESS_TTL  ?? 900);
    const refreshTtl = Number(process.env.JWT_REFRESH_TTL ?? 2_592_000);

    // role is in the JWT so the app knows immediately without a DB lookup
    const accessToken = await this.jwt.signAsync(
      { sub: userId, email, role },
      { secret: process.env.JWT_SECRET, expiresIn: accessTtl },
    );

    const rawRefresh = randomBytes(48).toString('hex');
    await this.refreshRepo.save(
      this.refreshRepo.create({
        userId,
        deviceId,
        tokenHash: this.hash(rawRefresh),
        expiresAt: new Date(Date.now() + refreshTtl * 1000),
      }),
    );

    return {
      accessToken,
      accessTokenExpiresIn: accessTtl,
      refreshToken: rawRefresh,
      role,
    };
  }

  private hash(token: string) {
    return createHash('sha256').update(token).digest('hex');
  }
}
