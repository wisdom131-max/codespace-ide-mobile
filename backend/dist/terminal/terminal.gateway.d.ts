import { OnGatewayConnection, OnGatewayDisconnect } from '@nestjs/websockets';
import { Socket } from 'socket.io';
export declare class TerminalGateway implements OnGatewayConnection, OnGatewayDisconnect {
    private sessions;
    handleConnection(client: Socket): void;
    handleDisconnect(client: Socket): void;
    onInput(client: Socket, msg: {
        data: string;
    }): void;
    onResize(client: Socket, msg: {
        cols: number;
        rows: number;
    }): void;
}
