export interface ConnectorDef {
  id: string;
  name: string;
  authUrl: string;
  tokenUrl: string;
  revokeUrl?: string;
  defaultScope: string;
  apiBase: string;
  clientIdEnv: string;
  clientSecretEnv: string;
  extraAuthParams?: Record<string, string>;
}

/**
 * Real, working OAuth 2.0 config per connector. The backend is the confidential client — it
 * holds the client_secret and does the code-for-token exchange server-side, so the Android app
 * never touches a client secret. One shared redirect URI (/api/v1/connectors/callback) is used
 * for all of them; the target service is embedded in the signed `state` param, so only ONE
 * redirect URI needs registering per provider console.
 */
export const CONNECTORS: Record<string, ConnectorDef> = {
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
};
