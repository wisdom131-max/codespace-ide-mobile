export interface IPty {
    pid: number;
    cols: number;
    rows: number;
    write(data: string): void;
    resize(columns: number, rows: number): void;
    kill(signal?: string): void;
    onData: (listener: (data: string) => void) => void;
    onExit: (listener: (e: {
        exitCode: number;
        signal?: string;
    }) => void) => void;
}
