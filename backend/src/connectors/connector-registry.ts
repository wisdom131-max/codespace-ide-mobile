export interface ConnectorDef {
  id: string;
  name: string;
  /**
   * 'oauth' (default) — browser consent flow, backend holds client_id/secret.
   * 'pat' — user pastes a personal API token; no OAuth app, no env vars needed.
   */
  authType: 'oauth' | 'pat';
  authUrl?: string;   // oauth only
  tokenUrl?: string;  // oauth only
  revokeUrl?: string; // oauth only
  defaultScope?: string; // oauth only; PAT rows store scope='pat'
  apiBase: string;
  clientIdEnv?: string;    // oauth only
  clientSecretEnv?: string; // oauth only
  extraAuthParams?: Record<string, string>;
  /**
   * How the code-for-token exchange (and refresh) talks to tokenUrl:
   * 'form'  — x-www-form-urlencoded body with client_id/secret (RFC 6749 default) — all standard providers.
   * 'json'  — JSON body with client_id/secret fields (Canva).
   * 'notion' — Authorization: Basic base64(id:secret) header + minimal JSON body (Notion).
   */
  tokenExchange?: 'form' | 'json' | 'notion';
  /** pat only — where the user creates the token */
  tokenHelpUrl?: string;
  /** pat only — expected token format hint shown in the paste dialog */
  tokenHint?: string;
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
    id: 'gmail', authType: 'oauth', name: 'Gmail',
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
    id: 'gcalendar', authType: 'oauth', name: 'Google Calendar',
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
    id: 'gdrive', authType: 'oauth', name: 'Google Drive',
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
    id: 'slack', name: 'Slack', authType: 'oauth',
    authUrl: 'https://slack.com/oauth/v2/authorize',
    tokenUrl: 'https://slack.com/api/oauth.v2.access',
    defaultScope: 'chat:write,channels:read,users:read',
    apiBase: 'https://slack.com/api',
    clientIdEnv: 'SLACK_CLIENT_ID',
    clientSecretEnv: 'SLACK_CLIENT_SECRET',
  },
  github: {
    id: 'github', name: 'GitHub', authType: 'oauth',
    authUrl: 'https://github.com/login/oauth/authorize',
    tokenUrl: 'https://github.com/login/oauth/access_token',
    // GitHub classic OAuth Apps have no revoke-by-POST endpoint like Google/Slack — revoking
    // requires an authenticated DELETE to /applications/{client_id}/grant, which is a different
    // shape (Basic auth with client_id:client_secret) than the other connectors' revokeUrl flow.
    // Skip it: disconnect() will just delete our local row; the grant stays valid GitHub-side
    // until the user revokes it themselves from github.com/settings/applications.
    defaultScope: 'repo read:user codespace',
    apiBase: 'https://api.github.com',
    clientIdEnv: 'GITHUB_OAUTH_CLIENT_ID',
    clientSecretEnv: 'GITHUB_OAUTH_CLIENT_SECRET',
  },

  // ── Phase 1 PAT connectors (Item 4, approved 2026-09-07) ────────────────────
  // User-pasted personal API tokens. No OAuth app, no server env vars — the
  // user brings their own token; it is encrypted at rest like OAuth tokens.
  // All seven use 'Authorization: Bearer <token>' so proxyCall works unchanged.
  sentry: {
    id: 'sentry', name: 'Sentry', authType: 'pat',
    apiBase: 'https://sentry.io/api/0',
    tokenHelpUrl: 'https://sentry.io/settings/account/api/auth-tokens/',
    tokenHint: 'sntrys_… (create with read scope)',
  },
  vercel: {
    id: 'vercel', name: 'Vercel', authType: 'pat',
    apiBase: 'https://api.vercel.com',
    tokenHelpUrl: 'https://vercel.com/account/tokens',
    tokenHint: 'personal access token',
  },
  cloudflare: {
    id: 'cloudflare', name: 'Cloudflare', authType: 'pat',
    apiBase: 'https://api.cloudflare.com/client/v4',
    tokenHelpUrl: 'https://dash.cloudflare.com/profile/api-tokens',
    tokenHint: 'API token (scoped, not the Global Key)',
  },
  posthog: {
    id: 'posthog', name: 'PostHog', authType: 'pat',
    // PostHog is regional; us is the default base — EU users pass full URLs in calls.
    apiBase: 'https://us.posthog.com',
    tokenHelpUrl: 'https://us.posthog.com/settings/personal-api-keys',
    tokenHint: 'phx_… personal API key',
  },
  stripe: {
    id: 'stripe', name: 'Stripe', authType: 'pat',
    apiBase: 'https://api.stripe.com',
    tokenHelpUrl: 'https://dashboard.stripe.com/apikeys',
    tokenHint: 'sk_test_… / sk_live_… restricted key',
  },
  railway: {
    id: 'railway', name: 'Railway', authType: 'pat',
    // Railway's API is GraphQL: POST { "query": "..." } to /graphql.
    apiBase: 'https://backboard.railway.com',
    tokenHelpUrl: 'https://railway.com/account/tokens',
    tokenHint: 'personal access token',
  },
  render: {
    id: 'render', name: 'Render', authType: 'pat',
    apiBase: 'https://api.render.com',
    tokenHelpUrl: 'https://dashboard.render.com/settings/api-keys',
    tokenHint: 'API key (rnd_…)',
  },

  // ── Phase 2 (Group A) OAuth2 connectors — approved 2026-09-07 ─────────────
  // Standard authorization-code flow against the shared redirect URI
  // https://codespace-ide-backend.onrender.com/api/v1/connectors/callback
  // Rows stay "Not set up yet" until the client id/secret env vars are set.
  gitlab: {
    id: 'gitlab', name: 'GitLab', authType: 'oauth',
    authUrl: 'https://gitlab.com/oauth/authorize',
    tokenUrl: 'https://gitlab.com/oauth/token',
    revokeUrl: 'https://gitlab.com/oauth/revoke',
    defaultScope: 'api',
    apiBase: 'https://gitlab.com/api/v4',
    clientIdEnv: 'GITLAB_OAUTH_CLIENT_ID',
    clientSecretEnv: 'GITLAB_OAUTH_CLIENT_SECRET',
  },
  notion: {
    id: 'notion', name: 'Notion', authType: 'oauth',
    // Notion uses no scope param (integration capabilities are set in the
    // Notion console) and a NONSTANDARD exchange: Basic-auth header + JSON body.
    authUrl: 'https://api.notion.com/v1/oauth/authorize',
    tokenUrl: 'https://api.notion.com/v1/oauth/token',
    apiBase: 'https://api.notion.com/v1',
    defaultScope: '',
    clientIdEnv: 'NOTION_OAUTH_CLIENT_ID',
    clientSecretEnv: 'NOTION_OAUTH_CLIENT_SECRET',
    tokenExchange: 'notion',
  },
  figma: {
    id: 'figma', name: 'Figma', authType: 'oauth',
    authUrl: 'https://www.figma.com/oauth',
    tokenUrl: 'https://www.figma.com/api/oauth/token',
    defaultScope: 'current_user:read file_content:read file_metadata:read '
      + 'file_comments:read file_comments:write file_versions:read '
      + 'file_dev_resources:read file_dev_resources:write '
      + 'projects:read project_metadata:read folders:read folder_metadata:read '
      + 'library_assets:read library_content:read team_library_content:read '
      + 'webhooks:read webhooks:write',
    apiBase: 'https://api.figma.com/v1',
    clientIdEnv: 'FIGMA_OAUTH_CLIENT_ID',
    clientSecretEnv: 'FIGMA_OAUTH_CLIENT_SECRET',
  },
  linear: {
    id: 'linear', name: 'Linear', authType: 'oauth',
    authUrl: 'https://linear.app/oauth/authorize',
    tokenUrl: 'https://api.linear.app/oauth/token',
    defaultScope: 'issues:create issues:read issues:update issues:delete '
      + 'comments:create comments:read comments:update comments:delete',
    apiBase: 'https://api.linear.app', // GraphQL — POST { "query": "…" } to /
    clientIdEnv: 'LINEAR_OAUTH_CLIENT_ID',
    clientSecretEnv: 'LINEAR_OAUTH_CLIENT_SECRET',
  },
  jira: {
    id: 'jira', name: 'Jira', authType: 'oauth',
    // Atlassian 3LO: audience param required on authorize; Bearer works for calls.
    authUrl: 'https://auth.atlassian.com/authorize',
    tokenUrl: 'https://auth.atlassian.com/oauth/token',
    defaultScope: 'read:jira-user read:jira-work write:jira-work '
      + 'manage:jira-project offline_access',
    apiBase: 'https://api.atlassian.com',
    clientIdEnv: 'JIRA_OAUTH_CLIENT_ID',
    clientSecretEnv: 'JIRA_OAUTH_CLIENT_SECRET',
    extraAuthParams: { audience: 'api.atlassian.com', prompt: 'consent' },
  },
  discord: {
    id: 'discord', name: 'Discord', authType: 'oauth',
    authUrl: 'https://discord.com/oauth2/authorize',
    tokenUrl: 'https://discord.com/api/oauth2/token',
    revokeUrl: 'https://discord.com/api/oauth2/token/revoke',
    defaultScope: 'identify email guilds guilds.members.read',
    apiBase: 'https://discord.com/api/v10',
    clientIdEnv: 'DISCORD_OAUTH_CLIENT_ID',
    clientSecretEnv: 'DISCORD_OAUTH_CLIENT_SECRET',
    extraAuthParams: { prompt: 'consent' }, // refresh tokens need re-consent on Discord
  },
  canva: {
    id: 'canva', name: 'Canva', authType: 'oauth',
    // Canva's token endpoint takes a JSON body (not form-encoded).
    authUrl: 'https://www.canva.com/api/oauth/authorize',
    tokenUrl: 'https://api.canva.com/api/v1/oauth/token',
    defaultScope: 'openid profile:read email design:meta:read design:content:read '
      + 'design:content:write asset:read asset:write folder:read folder:write '
      + 'comment:read comment:write',
    apiBase: 'https://api.canva.com/api/v1',
    clientIdEnv: 'CANVA_OAUTH_CLIENT_ID',
    clientSecretEnv: 'CANVA_OAUTH_CLIENT_SECRET',
    tokenExchange: 'json',
  },
  huggingface: {
    id: 'huggingface', name: 'Hugging Face', authType: 'oauth',
    authUrl: 'https://huggingface.co/oauth/authorize',
    tokenUrl: 'https://huggingface.co/oauth/token',
    revokeUrl: 'https://huggingface.co/oauth/revoke',
    defaultScope: 'openid profile email manage-repos write-discussions '
      + 'write-collections inference-api jobs write-endpoints webhooks '
      + 'read-billing read-memberships read-mcp',
    apiBase: 'https://huggingface.co/api',
    clientIdEnv: 'HUGGINGFACE_OAUTH_CLIENT_ID',
    clientSecretEnv: 'HUGGINGFACE_OAUTH_CLIENT_SECRET',
  },
};
