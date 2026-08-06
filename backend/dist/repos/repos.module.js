"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ReposModule = void 0;
const common_1 = require("@nestjs/common");
const typeorm_1 = require("@nestjs/typeorm");
const project_entity_1 = require("./project.entity");
const github_service_1 = require("./github.service");
const repos_controller_1 = require("./repos.controller");
const connectors_module_1 = require("../connectors/connectors.module");
let ReposModule = class ReposModule {
};
exports.ReposModule = ReposModule;
exports.ReposModule = ReposModule = __decorate([
    (0, common_1.Module)({
        imports: [typeorm_1.TypeOrmModule.forFeature([project_entity_1.Project]), connectors_module_1.ConnectorsModule],
        providers: [github_service_1.GithubService],
        controllers: [repos_controller_1.ReposController],
    })
], ReposModule);
//# sourceMappingURL=repos.module.js.map