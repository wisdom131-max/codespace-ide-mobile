import React, { useState, useRef, useEffect, useCallback } from 'react';
import {
  View, Text, StyleSheet, TouchableOpacity, ScrollView,
  TextInput, KeyboardAvoidingView, Platform, Alert,
  ActivityIndicator, Dimensions,
} from 'react-native';
import { MaterialCommunityIcons, Ionicons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../theme/colors';
import useStore from '../hooks/useStore';
import githubService from '../services/github';
import SyntaxHighlighter from '../components/SyntaxHighlighter';
import ActivityBar from '../components/ActivityBar';
import Sidebar from '../components/Sidebar';
import TabBar from '../components/TabBar';
import StatusBar from '../components/VSStatusBar';
import TerminalPanel from '../components/TerminalPanel';

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

export default function EditorScreen({ navigation }) {
  const {
    openFiles, activeFile, fileContents,
    sidebarVisible, panelVisible,
    setFileContent, markFileDirty,
    activeRepo, activeBranch, user,
  } = useStore();

  const [saving, setSaving] = useState(false);
  const [editorMode, setEditorMode] = useState('edit'); // 'edit' | 'preview'
  const [cursorLine, setCursorLine] = useState(1);
  const [cursorCol, setCursorCol] = useState(1);
  const inputRef = useRef(null);

  const currentContent = activeFile
    ? (fileContents[activeFile.path]?.content || '')
    : '';
  const isDirty = activeFile
    ? (fileContents[activeFile.path]?.isDirty || false)
    : false;

  const handleContentChange = useCallback((text) => {
    if (!activeFile) return;
    setFileContent(activeFile.path, text, fileContents[activeFile.path]?.sha);
    markFileDirty(activeFile.path, true);

    // Update cursor position
    const lines = text.split('\n');
    setCursorLine(lines.length);
    setCursorCol(lines[lines.length - 1].length + 1);
  }, [activeFile, fileContents]);

  const handleSave = async () => {
    if (!activeFile || !activeRepo || !isDirty) return;
    const fileData = fileContents[activeFile.path];
    if (!fileData) return;

    setSaving(true);
    try {
      const [owner, repo] = activeRepo.full_name.split('/');
      const result = await githubService.updateFile(
        owner, repo, activeFile.path,
        fileData.content, fileData.sha,
        `Update ${activeFile.name} via CodeSpace IDE`
      );
      setFileContent(activeFile.path, fileData.content, result.content.sha);
      markFileDirty(activeFile.path, false);
    } catch (err) {
      Alert.alert('Save Failed', err.message);
    } finally {
      setSaving(false);
    }
  };

  const getLanguage = (filename) => {
    const ext = filename?.split('.').pop()?.toLowerCase();
    const map = {
      js: 'javascript', jsx: 'jsx', ts: 'typescript', tsx: 'tsx',
      py: 'python', rb: 'ruby', java: 'java', kt: 'kotlin',
      swift: 'swift', go: 'go', rs: 'rust', cpp: 'cpp', c: 'c',
      cs: 'csharp', php: 'php', html: 'html', css: 'css',
      scss: 'scss', json: 'json', yaml: 'yaml', yml: 'yaml',
      md: 'markdown', sh: 'bash', sql: 'sql', xml: 'xml',
      dart: 'dart', vue: 'vue', svelte: 'svelte',
    };
    return map[ext] || 'plaintext';
  };

  const renderEmptyState = () => (
    <View style={styles.emptyState}>
      <MaterialCommunityIcons name="microsoft-visual-studio-code" size={80} color={Colors.bg_titlebar} />
      <Text style={styles.emptyTitle}>CodeSpace IDE</Text>
      <Text style={styles.emptySubtitle}>Open a file from the Explorer</Text>
      <View style={styles.shortcuts}>
        {[
          { key: 'Explorer', icon: 'folder-outline', action: 'Browse files' },
          { key: 'Search', icon: 'magnify', action: 'Find in files' },
          { key: 'Codespace', icon: 'cloud-outline', action: 'Connect codespace' },
          { key: 'Git', icon: 'source-branch', action: 'View changes' },
        ].map((item) => (
          <View key={item.key} style={styles.shortcutItem}>
            <MaterialCommunityIcons name={item.icon} size={20} color={Colors.text_secondary} />
            <Text style={styles.shortcutText}>{item.action}</Text>
          </View>
        ))}
      </View>
    </View>
  );

  return (
    <View style={styles.container}>
      {/* Activity Bar */}
      <ActivityBar navigation={navigation} />

      <View style={styles.main}>
        {/* Sidebar */}
        {sidebarVisible && <Sidebar navigation={navigation} />}

        {/* Editor Area */}
        <View style={styles.editorArea}>
          {/* Tab Bar */}
          {openFiles.length > 0 && <TabBar />}

          {/* Editor Toolbar */}
          {activeFile && (
            <View style={styles.editorToolbar}>
              <View style={styles.breadcrumb}>
                <Text style={styles.breadcrumbText} numberOfLines={1}>
                  {activeRepo?.name} › {activeFile.path}
                </Text>
              </View>
              <View style={styles.toolbarActions}>
                <TouchableOpacity
                  style={[styles.toolbarBtn, editorMode === 'preview' && styles.toolbarBtnActive]}
                  onPress={() => setEditorMode(editorMode === 'edit' ? 'preview' : 'edit')}
                >
                  <MaterialCommunityIcons
                    name={editorMode === 'edit' ? 'eye' : 'pencil'}
                    size={16}
                    color={Colors.text_secondary}
                  />
                </TouchableOpacity>

                <TouchableOpacity
                  style={[styles.toolbarBtn, isDirty && styles.toolbarBtnDirty]}
                  onPress={handleSave}
                  disabled={!isDirty || saving}
                >
                  {saving ? (
                    <ActivityIndicator size="small" color={Colors.accent} />
                  ) : (
                    <MaterialCommunityIcons
                      name="content-save"
                      size={16}
                      color={isDirty ? Colors.accent : Colors.text_secondary}
                    />
                  )}
                </TouchableOpacity>
              </View>
            </View>
          )}

          {/* Editor Content */}
          <KeyboardAvoidingView
            style={{ flex: 1 }}
            behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
          >
            {!activeFile ? (
              renderEmptyState()
            ) : editorMode === 'preview' ? (
              // Syntax highlighted preview
              <ScrollView style={styles.previewScroll} horizontal={false}>
                <ScrollView horizontal>
                  <SyntaxHighlighter
                    code={currentContent}
                    language={getLanguage(activeFile.name)}
                  />
                </ScrollView>
              </ScrollView>
            ) : (
              // Edit mode
              <View style={styles.editContainer}>
                {/* Line numbers */}
                <LineNumbers content={currentContent} />

                {/* Text editor */}
                <ScrollView style={{ flex: 1 }} keyboardDismissMode="none">
                  <TextInput
                    ref={inputRef}
                    style={styles.codeInput}
                    value={currentContent}
                    onChangeText={handleContentChange}
                    multiline
                    autoCapitalize="none"
                    autoCorrect={false}
                    autoComplete="off"
                    spellCheck={false}
                    keyboardType="default"
                    textAlignVertical="top"
                    scrollEnabled={false}
                    selectionColor={Colors.bg_selected}
                  />
                </ScrollView>
              </View>
            )}
          </KeyboardAvoidingView>

          {/* Terminal/Panel */}
          {panelVisible && <TerminalPanel />}
        </View>
      </View>

      {/* Status Bar */}
      <StatusBar
        branch={activeBranch}
        language={getLanguage(activeFile?.name)}
        line={cursorLine}
        col={cursorCol}
        isDirty={isDirty}
        repoName={activeRepo?.name}
      />
    </View>
  );
}

// Line numbers component
function LineNumbers({ content }) {
  const lines = content.split('\n');
  return (
    <ScrollView style={styles.lineNumbers} scrollEnabled={false}>
      {lines.map((_, i) => (
        <Text key={i} style={styles.lineNumber}>{i + 1}</Text>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: Colors.bg_editor },
  main: { flex: 1, flexDirection: 'row' },
  editorArea: { flex: 1, flexDirection: 'column' },

  editorToolbar: {
    height: 28, backgroundColor: Colors.bg_titlebar,
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: Spacing.sm, borderBottomWidth: 1, borderColor: Colors.border,
  },
  breadcrumb: { flex: 1 },
  breadcrumbText: { color: Colors.text_secondary, fontSize: FontSizes.xs },
  toolbarActions: { flexDirection: 'row', gap: 4 },
  toolbarBtn: {
    width: 24, height: 24, alignItems: 'center', justifyContent: 'center',
    borderRadius: 3,
  },
  toolbarBtnActive: { backgroundColor: Colors.bg_selected },
  toolbarBtnDirty: { backgroundColor: 'transparent' },

  editContainer: { flex: 1, flexDirection: 'row' },
  lineNumbers: {
    width: 40, backgroundColor: Colors.bg_editor,
    paddingTop: 4, paddingRight: 4,
  },
  lineNumber: {
    color: Colors.text_secondary, fontSize: 12, lineHeight: 20,
    textAlign: 'right', fontFamily: 'monospace',
  },
  codeInput: {
    flex: 1, color: Colors.text_primary,
    fontSize: 13, lineHeight: 20,
    fontFamily: 'monospace', padding: 4,
    paddingTop: 4, textAlignVertical: 'top',
  },
  previewScroll: { flex: 1, backgroundColor: Colors.bg_editor },

  emptyState: {
    flex: 1, alignItems: 'center', justifyContent: 'center', padding: Spacing.xl,
  },
  emptyTitle: { fontSize: 24, fontWeight: 'bold', color: Colors.text_secondary, marginTop: Spacing.md },
  emptySubtitle: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginTop: 4, marginBottom: Spacing.xl },
  shortcuts: { width: '100%', maxWidth: 260 },
  shortcutItem: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: Spacing.sm, gap: Spacing.sm,
  },
  shortcutText: { color: Colors.text_secondary, fontSize: FontSizes.sm },
});
