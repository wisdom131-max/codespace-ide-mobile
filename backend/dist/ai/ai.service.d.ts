export type ProviderId = 'openai' | 'claude' | 'gemini' | 'deepseek' | 'ollama';
interface ChatParams {
    provider: ProviderId;
    model: string;
    messages: {
        role: string;
        content: string;
    }[];
    apiKey?: string;
    baseUrl?: string;
}
export declare class AiService {
    chatStream(params: ChatParams): AsyncGenerator<string>;
    private openUpstream;
    private parse;
    private sse;
}
export {};
