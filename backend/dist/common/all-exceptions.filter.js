"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AllExceptionsFilter = void 0;
const common_1 = require("@nestjs/common");
const crypto_1 = require("crypto");
const STATUS_CODE_MAP = {
    400: 'VALIDATION_FAILED',
    401: 'UNAUTHORIZED',
    403: 'FORBIDDEN',
    404: 'RESOURCE_NOT_FOUND',
    409: 'CONFLICT',
    429: 'RATE_LIMITED',
    502: 'UPSTREAM_ERROR',
};
let AllExceptionsFilter = class AllExceptionsFilter {
    constructor() {
        this.logger = new common_1.Logger('Exception');
    }
    catch(exception, host) {
        const ctx = host.switchToHttp();
        const res = ctx.getResponse();
        const req = ctx.getRequest();
        const traceId = req.headers['x-trace-id'] ?? (0, crypto_1.randomUUID)();
        const status = exception instanceof common_1.HttpException
            ? exception.getStatus()
            : common_1.HttpStatus.INTERNAL_SERVER_ERROR;
        const raw = exception instanceof common_1.HttpException ? exception.getResponse() : null;
        const message = typeof raw === 'object' && raw !== null && 'message' in raw
            ? raw.message
            : status >= 500
                ? 'Internal server error'
                : exception?.message ?? 'Error';
        const code = STATUS_CODE_MAP[status] ?? 'INTERNAL';
        this.logger.error(`[${traceId}] ${status} ${code}`, exception?.stack);
        res.status(status).json({
            error: {
                code,
                message: Array.isArray(message) ? message.join('; ') : message,
                traceId,
                timestamp: new Date().toISOString(),
            },
        });
    }
};
exports.AllExceptionsFilter = AllExceptionsFilter;
exports.AllExceptionsFilter = AllExceptionsFilter = __decorate([
    (0, common_1.Catch)()
], AllExceptionsFilter);
//# sourceMappingURL=all-exceptions.filter.js.map