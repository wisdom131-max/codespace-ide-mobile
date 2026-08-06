export declare class HealthController {
    health(): {
        status: string;
        uptime: number;
    };
    ready(): {
        status: string;
    };
}
