"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.DatabaseModule = void 0;
const common_1 = require("@nestjs/common");
const typeorm_1 = require("@nestjs/typeorm");
const user_entity_1 = require("../users/user.entity");
const refresh_token_entity_1 = require("../auth/refresh-token.entity");
const project_entity_1 = require("../repos/project.entity");
let DatabaseModule = class DatabaseModule {
};
exports.DatabaseModule = DatabaseModule;
exports.DatabaseModule = DatabaseModule = __decorate([
    (0, common_1.Module)({
        imports: [
            typeorm_1.TypeOrmModule.forRootAsync({
                useFactory: () => ({
                    type: 'postgres',
                    url: process.env.DATABASE_URL,
                    entities: [user_entity_1.User, refresh_token_entity_1.RefreshToken, project_entity_1.Project],
                    synchronize: process.env.NODE_ENV !== 'production',
                    autoLoadEntities: true,
                    ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : false,
                    retryAttempts: 3,
                    retryDelay: 2000,
                    connectTimeoutMS: 10000,
                    keepConnectionAlive: true,
                }),
            }),
        ],
    })
], DatabaseModule);
//# sourceMappingURL=database.module.js.map