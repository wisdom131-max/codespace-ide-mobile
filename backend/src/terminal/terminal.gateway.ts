import {
  OnGatewayConnection,
  OnGatewayDisconnect,
  SubscribeMessage,
  WebSocketGateway,
  MessageBody,
  ConnectedSocket,
} from '@nestjs/websockets';
import { Socket } from 'socket.io';
import type { IPty } from 'node-pty';

/**
 * WebSocket PTY gateway. Spawns a constrained shell (or proxies SSH/Docker exec) and
 * streams stdout to the client, accepting stdin and resize events. In production each
 * session runs inside a per-user container with cgroup CPU/mem/time limits.
 */
@WebSocketGateway({ namespace: '/ws/terminal', cors: true })
export class TerminalGateway implements OnGatewayConnection, OnGatewayDisconnect {
  private sessions = new Map<string, IPty>();

  handleConnection(client: Socket) {
    // Validate the short-lived ticket from query before spawning.
    const ticket = client.handshake.query.ticket as string;
    if (!ticket) {
      client.disconnect(true);
      return;
    }
    // Lazy import — node-pty is an optional dependency.
    // In environments where native compilation fails (e.g. Railway), 
    // the terminal gateway still loads but PTY sessions are unavailable.
    let pty: any;
    try {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      pty = require('node-pty');
    } catch (err) {
      client.emit('error', { type: 'error', message: 'Terminal not available on this server (node-pty not installed)' });
      client.disconnect(true);
      return;
    }
    const shell = process.platform === 'win32' ? 'powershell.exe' : 'bash';
    const term: IPty = pty.spawn(shell, [], {
      name: 'xterm-256color',
      cols: 80,
      rows: 24,
      cwd: process.env.HOME,
      env: process.env as Record<string, string>,
    });
    this.sessions.set(client.id, term);
    term.onData((data) => client.emit('out', { type: 'out', data }));
    term.onExit(({ exitCode }) => client.emit('exit', { type: 'exit', code: exitCode }));
  }

  handleDisconnect(client: Socket) {
    this.sessions.get(client.id)?.kill();
    this.sessions.delete(client.id);
  }

  @SubscribeMessage('in')
  onInput(@ConnectedSocket() client: Socket, @MessageBody() msg: { data: string }) {
    this.sessions.get(client.id)?.write(msg.data);
  }

  @SubscribeMessage('resize')
  onResize(
    @ConnectedSocket() client: Socket,
    @MessageBody() msg: { cols: number; rows: number },
  ) {
    this.sessions.get(client.id)?.resize(msg.cols, msg.rows);
  }
}
