import React, { useState, useRef, useEffect } from 'react';
import {
  View, Text, TextInput, ScrollView, TouchableOpacity,
  StyleSheet, KeyboardAvoidingView, Platform,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../theme/colors';
import useStore from '../hooks/useStore';

export default function TerminalPanel() {
  const { panelTab, setPanelTab, togglePanel, activeCodespace } = useStore();
  const [terminalLines, setTerminalLines] = useState([
    { text: 'CodeSpace IDE Terminal', type: 'info' },
    { text: 'Connect a Codespace to use the interactive terminal.', type: 'dim' },
    { text: '─'.repeat(50), type: 'dim' },
    { text: '$ ', type: 'prompt' },
  ]);
  const [input, setInput] = useState('');
  const [history, setHistory] = useState([]);
  const [historyIdx, setHistoryIdx] = useState(-1);
  const scrollRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    scrollRef.current?.scrollToEnd({ animated: true });
  }, [terminalLines]);

  const handleSubmit = () => {
    if (!input.trim()) return;

    const cmd = input.trim();
    setHistory((h) => [cmd, ...h]);
    setHistoryIdx(-1);

    const newLines = [
      ...terminalLines.slice(0, -1),
      { text: `$ ${cmd}`, type: 'prompt' },
    ];

    // Simulate basic commands
    if (cmd === 'clear' || cmd === 'cls') {
      setTerminalLines([{ text: '$ ', type: 'prompt' }]);
      setInput('');
      return;
    }

    if (cmd === 'help') {
      newLines.push(
        { text: 'Available commands:', type: 'info' },
        { text: '  clear     Clear terminal', type: 'output' },
        { text: '  connect   Connect to active Codespace', type: 'output' },
        { text: '  status    Show Codespace status', type: 'output' },
        { text: '', type: 'output' },
        { text: 'For full terminal, connect a GitHub Codespace.', type: 'dim' },
      );
    } else if (cmd === 'status') {
      if (activeCodespace) {
        newLines.push(
          { text: `Codespace: ${activeCodespace.name}`, type: 'output' },
          { text: `Status: ${activeCodespace.state}`, type: 'success' },
          { text: `Machine: ${activeCodespace.machine?.display_name}`, type: 'output' },
        );
      } else {
        newLines.push({ text: 'No active codespace. Go to the Codespaces panel to connect.', type: 'error' });
      }
    } else {
      newLines.push({ text: `bash: ${cmd}: command not found (connect a Codespace for full terminal)`, type: 'error' });
    }

    newLines.push({ text: '$ ', type: 'prompt' });
    setTerminalLines(newLines);
    setInput('');
  };

  const lineColor = (type) => ({
    info:    Colors.info,
    dim:     Colors.text_secondary,
    prompt:  Colors.success,
    output:  Colors.text_primary,
    error:   Colors.error,
    success: Colors.success,
    warning: Colors.warning,
  }[type] || Colors.text_primary);

  const TABS = ['terminal', 'output', 'problems', 'debug'];

  return (
    <View style={styles.panel}>
      {/* Panel tab bar */}
      <View style={styles.tabBar}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.tabs}>
          {TABS.map((t) => (
            <TouchableOpacity
              key={t}
              style={[styles.tab, panelTab === t && styles.tabActive]}
              onPress={() => setPanelTab(t)}
            >
              <Text style={[styles.tabText, panelTab === t && styles.tabTextActive]}>
                {t.charAt(0).toUpperCase() + t.slice(1)}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>

        <View style={styles.tabActions}>
          <TouchableOpacity style={styles.iconBtn} onPress={() => setTerminalLines([{ text: '$ ', type: 'prompt' }])}>
            <MaterialCommunityIcons name="trash-can-outline" size={14} color={Colors.text_secondary} />
          </TouchableOpacity>
          <TouchableOpacity style={styles.iconBtn} onPress={togglePanel}>
            <MaterialCommunityIcons name="close" size={14} color={Colors.text_secondary} />
          </TouchableOpacity>
        </View>
      </View>

      {/* Terminal content */}
      {panelTab === 'terminal' && (
        <KeyboardAvoidingView
          style={styles.terminalContainer}
          behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        >
          <ScrollView
            ref={scrollRef}
            style={styles.output}
            onContentSizeChange={() => scrollRef.current?.scrollToEnd()}
          >
            {terminalLines.map((line, i) => (
              <Text key={i} style={[styles.termLine, { color: lineColor(line.type) }]}>
                {line.text}
              </Text>
            ))}
          </ScrollView>

          <View style={styles.inputRow}>
            <Text style={styles.promptChar}>$</Text>
            <TextInput
              ref={inputRef}
              style={styles.termInput}
              value={input}
              onChangeText={setInput}
              onSubmitEditing={handleSubmit}
              autoCapitalize="none"
              autoCorrect={false}
              returnKeyType="send"
              placeholder="Type command..."
              placeholderTextColor={Colors.text_secondary}
              blurOnSubmit={false}
            />
            <TouchableOpacity onPress={handleSubmit} style={styles.sendBtn}>
              <MaterialCommunityIcons name="send" size={16} color={Colors.accent} />
            </TouchableOpacity>
          </View>
        </KeyboardAvoidingView>
      )}

      {panelTab === 'problems' && (
        <View style={styles.emptyPanel}>
          <MaterialCommunityIcons name="check-circle-outline" size={28} color={Colors.text_secondary} />
          <Text style={styles.emptyText}>No problems detected</Text>
        </View>
      )}

      {panelTab === 'output' && (
        <ScrollView style={styles.output}>
          <Text style={styles.termLine}>Output will appear here.</Text>
        </ScrollView>
      )}

      {panelTab === 'debug' && (
        <View style={styles.emptyPanel}>
          <MaterialCommunityIcons name="bug-outline" size={28} color={Colors.text_secondary} />
          <Text style={styles.emptyText}>No debug session active</Text>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {
    height: 220,
    backgroundColor: Colors.terminal_bg,
    borderTopWidth: 1, borderColor: Colors.border,
  },
  tabBar: {
    height: 30, flexDirection: 'row',
    backgroundColor: Colors.bg_titlebar,
    borderBottomWidth: 1, borderColor: Colors.border,
    alignItems: 'center',
  },
  tabs: { flex: 1 },
  tab: {
    paddingHorizontal: 12, height: 30,
    justifyContent: 'center',
  },
  tabActive: { borderBottomWidth: 1, borderBottomColor: Colors.accent },
  tabText: { color: Colors.text_secondary, fontSize: FontSizes.xs },
  tabTextActive: { color: Colors.text_active },
  tabActions: { flexDirection: 'row', paddingHorizontal: 4 },
  iconBtn: { padding: 6 },

  terminalContainer: { flex: 1 },
  output: { flex: 1, padding: 6 },
  termLine: {
    fontFamily: 'monospace', fontSize: 12,
    lineHeight: 18, color: Colors.terminal_fg,
  },
  inputRow: {
    flexDirection: 'row', alignItems: 'center',
    borderTopWidth: 1, borderColor: Colors.border,
    paddingHorizontal: 8, paddingVertical: 4,
    backgroundColor: Colors.terminal_bg,
  },
  promptChar: { color: Colors.success, fontFamily: 'monospace', marginRight: 4 },
  termInput: {
    flex: 1, color: Colors.terminal_fg,
    fontFamily: 'monospace', fontSize: 12,
    height: 28,
  },
  sendBtn: { padding: 4 },

  emptyPanel: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  emptyText: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginTop: 8 },
});
