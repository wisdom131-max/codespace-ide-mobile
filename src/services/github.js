import axios from 'axios';
import * as SecureStore from 'expo-secure-store';

const GITHUB_API = 'https://api.github.com';
const GITHUB_AUTH_URL = 'https://github.com/login/oauth/authorize';
const CODESPACES_API = 'https://api.github.com/user/codespaces';

// GitHub OAuth App credentials (user must configure)
const CLIENT_ID = 'YOUR_GITHUB_OAUTH_CLIENT_ID'; // Replace after setup

class GitHubService {
  constructor() {
    this.token = null;
    this.client = axios.create({
      baseURL: GITHUB_API,
      headers: {
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
      },
    });

    // Intercept to inject token
    this.client.interceptors.request.use(async (config) => {
      const token = await this.getToken();
      if (token) {
        config.headers['Authorization'] = `Bearer ${token}`;
      }
      return config;
    });
  }

  async getToken() {
    if (this.token) return this.token;
    try {
      this.token = await SecureStore.getItemAsync('github_token');
      return this.token;
    } catch {
      return null;
    }
  }

  async setToken(token) {
    this.token = token;
    await SecureStore.setItemAsync('github_token', token);
  }

  async clearToken() {
    this.token = null;
    await SecureStore.deleteItemAsync('github_token');
  }

  async isAuthenticated() {
    const token = await this.getToken();
    return !!token;
  }

  // ─── User ───────────────────────────────────────────────
  async getUser() {
    const res = await this.client.get('/user');
    return res.data;
  }

  // ─── Repos ──────────────────────────────────────────────
  async getRepos(page = 1, perPage = 30) {
    const res = await this.client.get('/user/repos', {
      params: {
        sort: 'updated',
        per_page: perPage,
        page,
        affiliation: 'owner,collaborator',
      },
    });
    return res.data;
  }

  async searchRepos(query) {
    const user = await this.getUser();
    const res = await this.client.get('/search/repositories', {
      params: { q: `${query} user:${user.login}`, sort: 'updated' },
    });
    return res.data.items;
  }

  async getRepo(owner, repo) {
    const res = await this.client.get(`/repos/${owner}/${repo}`);
    return res.data;
  }

  // ─── Files & Content ────────────────────────────────────
  async getContents(owner, repo, path = '', ref = '') {
    const params = ref ? { ref } : {};
    const res = await this.client.get(`/repos/${owner}/${repo}/contents/${path}`, { params });
    return res.data;
  }

  async getFileContent(owner, repo, path, ref = '') {
    const params = ref ? { ref } : {};
    const res = await this.client.get(`/repos/${owner}/${repo}/contents/${path}`, { params });
    const content = res.data.content;
    const decoded = atob(content.replace(/\n/g, ''));
    return { content: decoded, sha: res.data.sha, encoding: res.data.encoding };
  }

  async updateFile(owner, repo, path, content, sha, message = 'Update via CodeSpace IDE') {
    const encoded = btoa(unescape(encodeURIComponent(content)));
    const res = await this.client.put(`/repos/${owner}/${repo}/contents/${path}`, {
      message,
      content: encoded,
      sha,
    });
    return res.data;
  }

  async createFile(owner, repo, path, content, message = 'Create via CodeSpace IDE') {
    const encoded = btoa(unescape(encodeURIComponent(content)));
    const res = await this.client.put(`/repos/${owner}/${repo}/contents/${path}`, {
      message,
      content: encoded,
    });
    return res.data;
  }

  async deleteFile(owner, repo, path, sha, message = 'Delete via CodeSpace IDE') {
    const res = await this.client.delete(`/repos/${owner}/${repo}/contents/${path}`, {
      data: { message, sha },
    });
    return res.data;
  }

  // ─── Branches ───────────────────────────────────────────
  async getBranches(owner, repo) {
    const res = await this.client.get(`/repos/${owner}/${repo}/branches`);
    return res.data;
  }

  async createBranch(owner, repo, branchName, fromSha) {
    const res = await this.client.post(`/repos/${owner}/${repo}/git/refs`, {
      ref: `refs/heads/${branchName}`,
      sha: fromSha,
    });
    return res.data;
  }

  // ─── Commits ────────────────────────────────────────────
  async getCommits(owner, repo, branch = 'main', perPage = 20) {
    const res = await this.client.get(`/repos/${owner}/${repo}/commits`, {
      params: { sha: branch, per_page: perPage },
    });
    return res.data;
  }

  async getCommitDiff(owner, repo, sha) {
    const res = await this.client.get(`/repos/${owner}/${repo}/commits/${sha}`, {
      headers: { Accept: 'application/vnd.github.diff' },
    });
    return res.data;
  }

  // ─── Pull Requests ──────────────────────────────────────
  async getPullRequests(owner, repo, state = 'open') {
    const res = await this.client.get(`/repos/${owner}/${repo}/pulls`, {
      params: { state, per_page: 20 },
    });
    return res.data;
  }

  async createPullRequest(owner, repo, title, body, head, base = 'main') {
    const res = await this.client.post(`/repos/${owner}/${repo}/pulls`, {
      title, body, head, base,
    });
    return res.data;
  }

  // ─── Issues ─────────────────────────────────────────────
  async getIssues(owner, repo, state = 'open') {
    const res = await this.client.get(`/repos/${owner}/${repo}/issues`, {
      params: { state, per_page: 20 },
    });
    return res.data;
  }

  async createIssue(owner, repo, title, body, labels = []) {
    const res = await this.client.post(`/repos/${owner}/${repo}/issues`, {
      title, body, labels,
    });
    return res.data;
  }

  // ─── Codespaces ─────────────────────────────────────────
  async getCodespaces() {
    const res = await this.client.get('/user/codespaces');
    return res.data.codespaces;
  }

  async createCodespace(owner, repo, branch = 'main', machine = 'basicLinux32gb') {
    const res = await this.client.post(`/repos/${owner}/${repo}/codespaces`, {
      ref: branch,
      machine,
    });
    return res.data;
  }

  async startCodespace(name) {
    const res = await this.client.post(`/user/codespaces/${name}/start`);
    return res.data;
  }

  async stopCodespace(name) {
    const res = await this.client.post(`/user/codespaces/${name}/stop`);
    return res.data;
  }

  async deleteCodespace(name) {
    await this.client.delete(`/user/codespaces/${name}`);
  }

  async getCodespaceUrl(name) {
    // Returns the VS Code Server URL for a running codespace
    const res = await this.client.get(`/user/codespaces/${name}`);
    return res.data;
  }

  // ─── Search ─────────────────────────────────────────────
  async searchCode(query, owner, repo) {
    const q = repo ? `${query} repo:${owner}/${repo}` : `${query} user:${owner}`;
    const res = await this.client.get('/search/code', {
      params: { q, per_page: 20 },
    });
    return res.data.items;
  }

  // ─── Gists ──────────────────────────────────────────────
  async getGists() {
    const res = await this.client.get('/gists');
    return res.data;
  }

  async createGist(description, filename, content, isPublic = false) {
    const res = await this.client.post('/gists', {
      description,
      public: isPublic,
      files: { [filename]: { content } },
    });
    return res.data;
  }
}

export const githubService = new GitHubService();
export default githubService;
