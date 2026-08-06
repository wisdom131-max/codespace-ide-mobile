"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AiService = void 0;
const common_1 = require("@nestjs/common");
let AiService = class AiService {
    async *chatStream(params) {
        const { provider } = params;
        const upstream = await this.openUpstream(params);
        if (!upstream.body) {
            yield this.sse('error', { message: `Upstream ${provider} returned no body` });
            return;
        }
        const reader = upstream.body.getReader?.();
        const decoder = new TextDecoder();
        if (!reader) {
            for await (const chunk of upstream.body) {
                for (const frame of this.parse(provider, decoder.decode(chunk))) {
                    yield frame;
                }
            }
            yield this.sse('done', { usage: {} });
            return;
        }
        while (true) {
            const { done, value } = await reader.read();
            if (done)
                break;
            for (const frame of this.parse(provider, decoder.decode(value))) {
                yield frame;
            }
        }
        yield this.sse('done', { usage: {} });
    }
    async openUpstream(p) {
        switch (p.provider) {
            case 'openai':
            case 'deepseek':
            case 'ollama': {
                const base = p.baseUrl ??
                    (p.provider === 'openai'
                        ? 'https://api.openai.com/v1'
                        : p.provider === 'deepseek'
                            ? 'https://api.deepseek.com/v1'
                            : process.env.OLLAMA_BASE_URL ?? 'http://localhost:11434/v1');
                return fetch(`${base}/chat/completions`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        ...(p.apiKey ? { Authorization: `Bearer ${p.apiKey}` } : {}),
                    },
                    body: JSON.stringify({ model: p.model, messages: p.messages, stream: true }),
                });
            }
            case 'claude':
                return fetch('https://api.anthropic.com/v1/messages', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'x-api-key': p.apiKey ?? '',
                        'anthropic-version': '2023-06-01',
                    },
                    body: JSON.stringify({
                        model: p.model,
                        max_tokens: 4096,
                        stream: true,
                        messages: p.messages,
                    }),
                });
            case 'gemini':
                return fetch(`https://generativelanguage.googleapis.com/v1beta/models/${p.model}:streamGenerateContent?alt=sse&key=${p.apiKey}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        contents: p.messages.map((m) => ({
                            role: m.role === 'assistant' ? 'model' : 'user',
                            parts: [{ text: m.content }],
                        })),
                    }),
                });
        }
    }
    parse(provider, text) {
        const out = [];
        for (const line of text.split('\n')) {
            if (!line.startsWith('data:'))
                continue;
            const data = line.slice(5).trim();
            if (!data || data === '[DONE]')
                continue;
            try {
                const json = JSON.parse(data);
                let delta = '';
                if (provider === 'claude') {
                    if (json.type === 'content_block_delta')
                        delta = json.delta?.text ?? '';
                }
                else if (provider === 'gemini') {
                    delta = json.candidates?.[0]?.content?.parts?.[0]?.text ?? '';
                }
                else {
                    delta = json.choices?.[0]?.delta?.content ?? '';
                }
                if (delta)
                    out.push(this.sse('token', { delta }));
            }
            catch {
            }
        }
        return out;
    }
    sse(event, data) {
        return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    }
};
exports.AiService = AiService;
exports.AiService = AiService = __decorate([
    (0, common_1.Injectable)()
], AiService);
//# sourceMappingURL=ai.service.js.map