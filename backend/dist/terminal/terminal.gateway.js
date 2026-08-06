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
exports.TerminalGateway = void 0;
const websockets_1 = require("@nestjs/websockets");
const socket_io_1 = require("socket.io");
let TerminalGateway = class TerminalGateway {
    constructor() {
        this.sessions = new Map();
    }
    handleConnection(client) {
        const ticket = client.handshake.query.ticket;
        if (!ticket) {
            client.disconnect(true);
            return;
        }
        let pty;
        try {
            pty = require('node-pty');
        }
        catch (err) {
            client.emit('error', { type: 'error', message: 'Terminal not available on this server (node-pty not installed)' });
            client.disconnect(true);
            return;
        }
        const shell = process.platform === 'win32' ? 'powershell.exe' : 'bash';
        const term = pty.spawn(shell, [], {
            name: 'xterm-256color',
            cols: 80,
            rows: 24,
            cwd: process.env.HOME,
            env: process.env,
        });
        this.sessions.set(client.id, term);
        term.onData((data) => client.emit('out', { type: 'out', data }));
        term.onExit(({ exitCode }) => client.emit('exit', { type: 'exit', code: exitCode }));
    }
    handleDisconnect(client) {
        this.sessions.get(client.id)?.kill();
        this.sessions.delete(client.id);
    }
    onInput(client, msg) {
        this.sessions.get(client.id)?.write(msg.data);
    }
    onResize(client, msg) {
        this.sessions.get(client.id)?.resize(msg.cols, msg.rows);
    }
};
exports.TerminalGateway = TerminalGateway;
__decorate([
    (0, websockets_1.SubscribeMessage)('in'),
    __param(0, (0, websockets_1.ConnectedSocket)()),
    __param(1, (0, websockets_1.MessageBody)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], TerminalGateway.prototype, "onInput", null);
__decorate([
    (0, websockets_1.SubscribeMessage)('resize'),
    __param(0, (0, websockets_1.ConnectedSocket)()),
    __param(1, (0, websockets_1.MessageBody)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], TerminalGateway.prototype, "onResize", null);
exports.TerminalGateway = TerminalGateway = __decorate([
    (0, websockets_1.WebSocketGateway)({ namespace: '/ws/terminal', cors: true })
], TerminalGateway);
//# sourceMappingURL=terminal.gateway.js.map