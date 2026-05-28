import { githubService } from './github';

/**
 * CodespaceManager — handles connecting to a running GitHub Codespace
 * via the VS Code Server tunnel URL.
 *
 * GitHub Codespaces expose a VS Code Server that can be accessed via:
 * https://<codespace-name>.github.dev  (web editor)
 * wss://<codespace-name>.github.dev  (WebSocket for terminal/lsp)
 *
 * This service manages:
 * 1. Listing and starting codespaces
 * 2. Getting the tunnel URL
 * 3. WebSocket connection for terminal
 * 4. File operations via the Codespace API
 */

export class CodespaceManager {
  constructor() {
    this.activeCodespace = null;
    this.wsConnection = null;
    this.onMessage = null;
    this.onClose = null;
    this.onError = null;
  }

  async listCodespaces() {
    return await githubService.getCodespaces();
  }

  async startCodespace(name) {
    const cs = await githubService.startCodespace(name);
    this.activeCodespace = cs;
    return cs;
  }

  async stopCodespace(name) {
    return await githubService.stopCodespace(name);
  }

  getCodespaceWebUrl(name) {
    return `https://${name}.github.dev`;
  }

  getVSCodeServerUrl(name) {
    // VS Code for Web URL - opens in WebView
    return `https://github.dev/${name}`;
  }

  // Connect to Codespace terminal via WebSocket
  connectTerminal(codespaceName, token, onData, onClose, onError) {
    const wsUrl = `wss://${codespaceName}.github.dev/terminal`;

    try {
      this.wsConnection = new WebSocket(wsUrl, [], {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });

      this.wsConnection.onmessage = (e) => {
        if (onData) onData(e.data);
      };

      this.wsConnection.onclose = (e) => {
        if (onClose) onClose(e);
      };

      this.wsConnection.onerror = (e) => {
        if (onError) onError(e);
      };

      return this.wsConnection;
    } catch (err) {
      if (onError) onError(err);
      return null;
    }
  }

  sendTerminalInput(data) {
    if (this.wsConnection && this.wsConnection.readyState === WebSocket.OPEN) {
      this.wsConnection.send(JSON.stringify({ type: 'input', data }));
    }
  }

  disconnectTerminal() {
    if (this.wsConnection) {
      this.wsConnection.close();
      this.wsConnection = null;
    }
  }

  // Get machine types available
  getMachineTypes() {
    return [
      { id: 'basicLinux32gb', label: 'Basic (2 cores, 4GB RAM)', cores: 2, ram: 4 },
      { id: 'standardLinux32gb', label: 'Standard (4 cores, 8GB RAM)', cores: 4, ram: 8 },
      { id: 'premiumLinux', label: 'Premium (8 cores, 16GB RAM)', cores: 8, ram: 16 },
    ];
  }

  // Get codespace status label
  getStatusLabel(status) {
    const labels = {
      Available: 'Running',
      Starting: 'Starting...',
      Stopping: 'Stopping...',
      Stopped: 'Stopped',
      Deleted: 'Deleted',
      Unknown: 'Unknown',
    };
    return labels[status] || status;
  }

  getStatusColor(status) {
    const colors = {
      Available: '#73c991',
      Starting: '#cca700',
      Stopping: '#cca700',
      Stopped: '#858585',
      Deleted: '#f48771',
    };
    return colors[status] || '#858585';
  }
}

export const codespaceManager = new CodespaceManager();
export default codespaceManager;
