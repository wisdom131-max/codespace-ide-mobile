"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CONNECTORS = void 0;
exports.CONNECTORS = {
    gmail: {
        id: 'gmail', name: 'Gmail',
        authUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        tokenUrl: 'https://oauth2.googleapis.com/token',
        revokeUrl: 'https://oauth2.googleapis.com/revoke',
        defaultScope: 'https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/userinfo.email',
        apiBase: 'https://gmail.googleapis.com/gmail/v1',
        clientIdEnv: 'GOOGLE_OAUTH_CLIENT_ID',
        clientSecretEnv: 'GOOGLE_OAUTH_CLIENT_SECRET',
        extraAuthParams: { access_type: 'offline', prompt: 'consent' },
    },
    gcalendar: {
        id: 'gcalendar', name: 'Google Calendar',
        authUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        tokenUrl: 'https://oauth2.googleapis.com/token',
        revokeUrl: 'https://oauth2.googleapis.com/revoke',
        defaultScope: 'https://www.googleapis.com/auth/calendar',
        apiBase: 'https://www.googleapis.com/calendar/v3',
        clientIdEnv: 'GOOGLE_OAUTH_CLIENT_ID',
        clientSecretEnv: 'GOOGLE_OAUTH_CLIENT_SECRET',
        extraAuthParams: { access_type: 'offline', prompt: 'consent' },
    },
    gdrive: {
        id: 'gdrive', name: 'Google Drive',
        authUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
        tokenUrl: 'https://oauth2.googleapis.com/token',
        revokeUrl: 'https://oauth2.googleapis.com/revoke',
        defaultScope: 'https://www.googleapis.com/auth/drive',
        apiBase: 'https://www.googleapis.com/drive/v3',
        clientIdEnv: 'GOOGLE_OAUTH_CLIENT_ID',
        clientSecretEnv: 'GOOGLE_OAUTH_CLIENT_SECRET',
        extraAuthParams: { access_type: 'offline', prompt: 'consent' },
    },
    slack: {
        id: 'slack', name: 'Slack',
        authUrl: 'https://slack.com/oauth/v2/authorize',
        tokenUrl: 'https://slack.com/api/oauth.v2.access',
        defaultScope: 'chat:write,channels:read,users:read',
        apiBase: 'https://slack.com/api',
        clientIdEnv: 'SLACK_CLIENT_ID',
        clientSecretEnv: 'SLACK_CLIENT_SECRET',
    },
    github: {
        id: 'github', name: 'GitHub',
        authUrl: 'https://github.com/login/oauth/authorize',
        tokenUrl: 'https://github.com/login/oauth/access_token',
        defaultScope: 'repo read:user codespace',
        apiBase: 'https://api.github.com',
        clientIdEnv: 'GITHUB_OAUTH_CLIENT_ID',
        clientSecretEnv: 'GITHUB_OAUTH_CLIENT_SECRET',
    },
};
//# sourceMappingURL=connector-registry.js.map