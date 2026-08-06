"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ProjectsService = void 0;
const common_1 = require("@nestjs/common");
const typeorm_1 = require("@nestjs/typeorm");
const typeorm_2 = require("typeorm");
const project_entity_1 = require("./project.entity");
let ProjectsService = class ProjectsService {
    constructor(repo) {
        this.repo = repo;
    }
    listForUser(ownerId) {
        return this.repo.find({
            where: { ownerId },
            order: { updatedAt: 'DESC' },
        });
    }
    async upsert(ownerId, data) {
        const existing = data.id
            ? await this.repo.findOne({ where: { id: data.id, ownerId } })
            : null;
        const entity = {
            id: data.id ?? String(Date.now()),
            name: data.name,
            kind: data.kind ?? project_entity_1.ProjectKind.LOCAL,
            pathOrUrl: data.pathOrUrl ?? 'local',
            defaultBranch: data.defaultBranch ?? 'main',
            ownerId,
        };
        if (existing) {
            await this.repo.update({ id: entity.id, ownerId }, entity);
            return this.repo.findOne({ where: { id: entity.id, ownerId } });
        }
        return this.repo.save(this.repo.create(entity));
    }
    async remove(ownerId, id) {
        const result = await this.repo.delete({ id, ownerId });
        if (!result.affected)
            throw new common_1.NotFoundException('Project not found');
        return { deleted: true };
    }
};
exports.ProjectsService = ProjectsService;
exports.ProjectsService = ProjectsService = __decorate([
    (0, common_1.Injectable)(),
    __param(0, (0, typeorm_1.InjectRepository)(project_entity_1.Project)),
    __metadata("design:paramtypes", [typeorm_2.Repository])
], ProjectsService);
//# sourceMappingURL=projects.service.js.map