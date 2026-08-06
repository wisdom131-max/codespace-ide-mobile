"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.SyncService = void 0;
const common_1 = require("@nestjs/common");
let SyncService = class SyncService {
    constructor() {
        this.revs = new Map();
    }
    pull(projectId, sinceRev) {
        const current = this.revs.get(projectId) ?? 0;
        return { rev: current, changes: [], hasMore: false, sinceRev };
    }
    push(projectId, clientRev, changes) {
        const serverRev = this.revs.get(projectId) ?? 0;
        if (clientRev < serverRev) {
            return { accepted: false, conflicts: changes.map((c) => c.path), serverRev };
        }
        const newRev = serverRev + 1;
        this.revs.set(projectId, newRev);
        return { accepted: true, rev: newRev, applied: changes.length };
    }
};
exports.SyncService = SyncService;
exports.SyncService = SyncService = __decorate([
    (0, common_1.Injectable)()
], SyncService);
//# sourceMappingURL=sync.service.js.map