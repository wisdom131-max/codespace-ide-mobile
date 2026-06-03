// VS Code Light+ Theme Colors (default)
export const LightColors = {
  bg_editor: '#ffffff',
  bg_sidebar: '#f3f3f3',
  bg_activitybar: '#2c2c2c',
  bg_panel: '#f3f3f3',
  bg_statusbar: '#007acc',
  bg_titlebar: '#dddddd',
  bg_tab_active: '#ffffff',
  bg_tab_inactive: '#ececec',
  bg_input: '#ffffff',
  bg_dropdown: '#f3f3f3',
  bg_hover: '#e8e8e8',
  bg_selected: '#cce5ff',
  bg_highlight: '#add6ff40',
  bg_modal: '#f3f3f3',

  text_primary: '#1e1e1e',
  text_secondary: '#6e6e6e',
  text_active: '#ffffff',
  text_inactive: '#aaaaaa',
  text_link: '#006ab1',
  text_error: '#e51400',
  text_warning: '#bf8803',
  text_info: '#1a85ff',

  syntax_keyword: '#0000ff',
  syntax_string: '#a31515',
  syntax_number: '#098658',
  syntax_comment: '#008000',
  syntax_function: '#795e26',
  syntax_variable: '#001080',
  syntax_type: '#267f99',
  syntax_class: '#267f99',
  syntax_operator: '#1e1e1e',
  syntax_punctuation: '#1e1e1e',
  syntax_tag: '#800000',
  syntax_attribute: '#e50000',
  syntax_property: '#001080',
  syntax_constant: '#0070c1',

  border: '#d4d4d4',
  border_focus: '#007acc',
  border_subtle: '#e5e5e5',
  accent: '#007acc',
  accent_hover: '#1a85ff',
  success: '#388a34',
  error: '#e51400',
  warning: '#bf8803',
  info: '#1a85ff',

  git_added: '#388a34',
  git_modified: '#895503',
  git_deleted: '#e51400',
  git_untracked: '#388a34',
  git_conflict: '#e4676b',

  terminal_bg: '#1e1e1e',
  terminal_fg: '#cccccc',
  terminal_cursor: '#aeafad',
  terminal_selection: '#264f78',
  terminal_black: '#000000',
  terminal_red: '#cd3131',
  terminal_green: '#0dbc79',
  terminal_yellow: '#e5e510',
  terminal_blue: '#2472c8',
  terminal_magenta: '#bc3fbc',
  terminal_cyan: '#11a8cd',
  terminal_white: '#e5e5e5',
};

// VS Code Dark+ Theme Colors
export const DarkColors = {
  bg_editor: '#1e1e1e',
  bg_sidebar: '#252526',
  bg_activitybar: '#333333',
  bg_panel: '#1e1e1e',
  bg_statusbar: '#007acc',
  bg_titlebar: '#3c3c3c',
  bg_tab_active: '#1e1e1e',
  bg_tab_inactive: '#2d2d2d',
  bg_input: '#3c3c3c',
  bg_dropdown: '#252526',
  bg_hover: '#2a2d2e',
  bg_selected: '#094771',
  bg_highlight: '#add6ff26',
  bg_modal: '#252526',

  text_primary: '#cccccc',
  text_secondary: '#858585',
  text_active: '#ffffff',
  text_inactive: '#888888',
  text_link: '#3794ff',
  text_error: '#f48771',
  text_warning: '#cca700',
  text_info: '#75beff',

  syntax_keyword: '#569cd6',
  syntax_string: '#ce9178',
  syntax_number: '#b5cea8',
  syntax_comment: '#6a9955',
  syntax_function: '#dcdcaa',
  syntax_variable: '#9cdcfe',
  syntax_type: '#4ec9b0',
  syntax_class: '#4ec9b0',
  syntax_operator: '#d4d4d4',
  syntax_punctuation: '#d4d4d4',
  syntax_tag: '#569cd6',
  syntax_attribute: '#9cdcfe',
  syntax_property: '#9cdcfe',
  syntax_constant: '#4fc1ff',

  border: '#474747',
  border_focus: '#007acc',
  border_subtle: '#3c3c3c',
  accent: '#007acc',
  accent_hover: '#1a85ff',
  success: '#73c991',
  error: '#f48771',
  warning: '#cca700',
  info: '#75beff',

  git_added: '#73c991',
  git_modified: '#e2c08d',
  git_deleted: '#f44747',
  git_untracked: '#73c991',
  git_conflict: '#e4676b',

  terminal_bg: '#1e1e1e',
  terminal_fg: '#cccccc',
  terminal_cursor: '#aeafad',
  terminal_selection: '#264f78',
  terminal_black: '#000000',
  terminal_red: '#cd3131',
  terminal_green: '#0dbc79',
  terminal_yellow: '#e5e510',
  terminal_blue: '#2472c8',
  terminal_magenta: '#bc3fbc',
  terminal_cyan: '#11a8cd',
  terminal_white: '#e5e5e5',
};

// AMOLED Black Theme
export const AmoledColors = {
  ...DarkColors,
  bg_editor: '#000000',
  bg_sidebar: '#0a0a0a',
  bg_activitybar: '#000000',
  bg_panel: '#000000',
  bg_titlebar: '#111111',
  bg_tab_active: '#000000',
  bg_tab_inactive: '#0d0d0d',
  bg_input: '#111111',
  bg_dropdown: '#0a0a0a',
  bg_hover: '#1a1a1a',
  bg_modal: '#0a0a0a',
  border: '#222222',
  border_subtle: '#111111',
};

// Active theme — starts as Light
export let Colors = { ...LightColors };

export const THEMES = {
  light: LightColors,
  dark: DarkColors,
  amoled: AmoledColors,
};

export function applyTheme(themeName) {
  const theme = THEMES[themeName] || LightColors;
  Object.assign(Colors, theme);
}

export const FontSizes = {
  xs: 10,
  sm: 12,
  md: 14,
  lg: 16,
  xl: 18,
  xxl: 24,
};

export const Spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
};
