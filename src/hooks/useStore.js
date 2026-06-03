import { create } from 'zustand';
import { applyTheme } from '../theme/colors';

export const useStore = create((set, get) => ({
  // ─── Auth ────────────────────────────────────────────────
  user: null,
  isAuthenticated: false,
  setUser: (user) => set({ user, isAuthenticated: !!user }),
  clearUser: () => set({ user: null, isAuthenticated: false }),

  // ─── Active Repo / Codespace ────────────────────────────
  activeRepo: null,
  activeBranch: 'main',
  activeCodespace: null,
  setActiveRepo: (repo) => set({ activeRepo: repo }),
  setActiveBranch: (branch) => set({ activeBranch: branch }),
  setActiveCodespace: (cs) => set({ activeCodespace: cs }),

  // ─── Open Files (Tabs) ──────────────────────────────────
  openFiles: [],
  activeFile: null,
  fileContents: {},

  openFile: (file) => {
    const { openFiles } = get();
    const exists = openFiles.find((f) => f.path === file.path);
    if (!exists) {
      set({ openFiles: [...openFiles, file], activeFile: file });
    } else {
      set({ activeFile: exists });
    }
  },

  closeFile: (path) => {
    const { openFiles, activeFile } = get();
    const filtered = openFiles.filter((f) => f.path !== path);
    let newActive = activeFile;
    if (activeFile?.path === path) {
      const idx = openFiles.findIndex((f) => f.path === path);
      newActive = filtered[idx] || filtered[idx - 1] || null;
    }
    set({ openFiles: filtered, activeFile: newActive });
  },

  setActiveFile: (file) => set({ activeFile: file }),

  setFileContent: (path, content, sha = null) => {
    const { fileContents } = get();
    set({
      fileContents: {
        ...fileContents,
        [path]: { ...fileContents[path], content, sha },
      },
    });
  },

  markFileDirty: (path, isDirty) => {
    const { fileContents } = get();
    set({
      fileContents: {
        ...fileContents,
        [path]: { ...fileContents[path], isDirty },
      },
    });
  },

  // ─── UI State ────────────────────────────────────────────
  sidebarVisible: true,
  sidebarTab: 'explorer',
  panelVisible: false,
  panelTab: 'terminal',
  activityBarVisible: true,

  toggleSidebar: () => set((s) => ({ sidebarVisible: !s.sidebarVisible })),
  setSidebarTab: (tab) => set({ sidebarTab: tab, sidebarVisible: true }),
  togglePanel: () => set((s) => ({ panelVisible: !s.panelVisible })),
  setPanelTab: (tab) => set({ panelTab: tab, panelVisible: true }),

  // ─── Theme ──────────────────────────────────────────────
  theme: 'light',
  fontSize: 14,
  fontFamily: 'monospace',
  setTheme: (themeName) => {
    applyTheme(themeName);
    set({ theme: themeName });
  },
  setFontSize: (size) => set({ fontSize: size }),

  // ─── Search ─────────────────────────────────────────────
  searchQuery: '',
  searchResults: [],
  setSearchQuery: (q) => set({ searchQuery: q }),
  setSearchResults: (r) => set({ searchResults: r }),

  // ─── Git State ──────────────────────────────────────────
  gitChanges: [],
  gitBranches: [],
  setGitChanges: (changes) => set({ gitChanges: changes }),
  setGitBranches: (branches) => set({ gitBranches: branches }),

  // ─── Notifications ──────────────────────────────────────
  notifications: [],
  addNotification: (n) =>
    set((s) => ({ notifications: [...s.notifications, { ...n, id: Date.now() }] })),
  removeNotification: (id) =>
    set((s) => ({ notifications: s.notifications.filter((n) => n.id !== id) })),

  // ─── Extensions ─────────────────────────────────────────
  installedExtensions: [],
  installExtension: (ext) => {
    const { installedExtensions, setTheme } = get();
    const already = installedExtensions.find(e => e.id === ext.id);
    if (!already) {
      set({ installedExtensions: [...installedExtensions, { ...ext, installed: true }] });
      // If it's a theme extension, apply it
      if (ext.themeKey) setTheme(ext.themeKey);
    }
  },
  uninstallExtension: (id) => {
    set((s) => ({ installedExtensions: s.installedExtensions.filter(e => e.id !== id) }));
  },

  // ─── AI Chat ────────────────────────────────────────────
  aiProvider: 'copilot', // 'copilot' | 'claude' | 'openai' | 'gemini' | 'deepseek'
  aiApiKeys: {},
  chatHistory: [],
  setAiProvider: (provider) => set({ aiProvider: provider }),
  setAiApiKey: (provider, key) => set((s) => ({
    aiApiKeys: { ...s.aiApiKeys, [provider]: key }
  })),
  addChatMessage: (msg) => set((s) => ({
    chatHistory: [...s.chatHistory, { ...msg, id: Date.now() }]
  })),
  clearChat: () => set({ chatHistory: [] }),
}));

export default useStore;
