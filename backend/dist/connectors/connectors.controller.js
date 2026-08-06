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
exports.ConnectorsController = void 0;
const common_1 = require("@nestjs/common");
const swagger_1 = require("@nestjs/swagger");
const jwt_auth_guard_1 = require("../auth/jwt-auth.guard");
const connectors_service_1 = require("./connectors.service");
let ConnectorsController = class ConnectorsController {
    constructor(connectors) {
        this.connectors = connectors;
    }
    status(req) {
        return this.connectors.statusForUser(req.user.userId);
    }
    authUrl(req, service) {
        return { authUrl: this.connectors.getAuthUrl(req.user.userId, service) };
    }
    async callback(code, state, error, res) {
        const result = await this.connectors.handleCallback(code, state, error);
        res
            .status(200)
            .type('html')
            .send(`<!DOCTYPE html><html><head><title>CodeSpace IDE</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:-apple-system,system-ui,sans-serif;display:flex;align-items:center;
        justify-content:center;height:100vh;margin:0;background:#111;color:#eee;text-align:center}
        div{max-width:360px;padding:24px}</style></head>
        <body><div><h2>${result.ok ? '✅ Connected' : '⚠️ Something went wrong'}</h2>
        <p>${result.message}</p></div></body></html>`);
    }
    call(req, service, body) {
        return this.connectors.proxyCall(req.user.userId, service, body.method, body.path, body.body);
    }
    disconnect(req, service) {
        return this.connectors.disconnect(req.user.userId, service);
    }
};
exports.ConnectorsController = ConnectorsController;
__decorate([
    (0, common_1.Get)(),
    (0, swagger_1.ApiBearerAuth)(),
    (0, common_1.UseGuards)(jwt_auth_guard_1.JwtAuthGuard),
    __param(0, (0, common_1.Req)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", void 0)
], ConnectorsController.prototype, "status", null);
__decorate([
    (0, common_1.Get)(':service/auth-url'),
    (0, swagger_1.ApiBearerAuth)(),
    (0, common_1.UseGuards)(jwt_auth_guard_1.JwtAuthGuard),
    __param(0, (0, common_1.Req)()),
    __param(1, (0, common_1.Param)('service')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, String]),
    __metadata("design:returntype", void 0)
], ConnectorsController.prototype, "authUrl", null);
__decorate([
    (0, common_1.Get)('callback'),
    __param(0, (0, common_1.Query)('code')),
    __param(1, (0, common_1.Query)('state')),
    __param(2, (0, common_1.Query)('error')),
    __param(3, (0, common_1.Res)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String, String, Object]),
    __metadata("design:returntype", Promise)
], ConnectorsController.prototype, "callback", null);
__decorate([
    (0, common_1.Post)(':service/call'),
    (0, swagger_1.ApiBearerAuth)(),
    (0, common_1.UseGuards)(jwt_auth_guard_1.JwtAuthGuard),
    __param(0, (0, common_1.Req)()),
    __param(1, (0, common_1.Param)('service')),
    __param(2, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, String, Object]),
    __metadata("design:returntype", void 0)
], ConnectorsController.prototype, "call", null);
__decorate([
    (0, common_1.Delete)(':service'),
    (0, swagger_1.ApiBearerAuth)(),
    (0, common_1.UseGuards)(jwt_auth_guard_1.JwtAuthGuard),
    __param(0, (0, common_1.Req)()),
    __param(1, (0, common_1.Param)('service')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, String]),
    __metadata("design:returntype", void 0)
], ConnectorsController.prototype, "disconnect", null);
exports.ConnectorsController = ConnectorsController = __decorate([
    (0, swagger_1.ApiTags)('connectors'),
    (0, common_1.Controller)('connectors'),
    __metadata("design:paramtypes", [connectors_service_1.ConnectorsService])
], ConnectorsController);
//# sourceMappingURL=connectors.controller.js.map