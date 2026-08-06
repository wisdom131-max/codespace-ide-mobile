"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.GithubService = void 0;
const common_1 = require("@nestjs/common");
const octokit_1 = require("octokit");
let GithubService = class GithubService {
    client(token) {
        return new octokit_1.Octokit({ auth: token });
    }
    async listRepos(token) {
        const octokit = this.client(token);
        const { data } = await octokit.rest.repos.listForAuthenticatedUser({
            per_page: 100,
            sort: 'updated',
        });
        return data.map((r) => ({
            id: r.id,
            name: r.name,
            fullName: r.full_name,
            defaultBranch: r.default_branch,
            private: r.private,
        }));
    }
    async createPullRequest(token, owner, repo, body) {
        const octokit = this.client(token);
        const { data } = await octokit.rest.pulls.create({ owner, repo, ...body });
        return { number: data.number, title: data.title, state: data.state, url: data.html_url };
    }
    async listCodespaces(token) {
        const octokit = this.client(token);
        const { data } = await octokit.rest.codespaces.listForAuthenticatedUser();
        return data.codespaces.map((c) => ({
            name: c.name,
            state: c.state,
            repo: c.repository?.full_name,
        }));
    }
};
exports.GithubService = GithubService;
exports.GithubService = GithubService = __decorate([
    (0, common_1.Injectable)()
], GithubService);
//# sourceMappingURL=github.service.js.map