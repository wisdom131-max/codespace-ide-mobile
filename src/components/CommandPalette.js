import React, { useState, useMemo } from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  ScrollView, StyleSheet, Modal,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes } from '../theme/colors';
import useStore from '../hooks/useStore';

const COMMANDS = [
  { id: 'explorer',    label: 'View: Show Explorer',        icon: 'file-multiple-outline',  group: 'View' },
  { id: 'search',      label: 'View: Show Search',          icon: 'magnify',                group: 'View' },
  { id: 'git',         label: 'View: Show Source Control',  icon: 'source-branch',          group: 'View' },
  { id: 'extensions',  label: 'View: Show Extensions',      icon: 'puzzle-outline',         group: 'View' },
  { id: 'terminal',    label: 'Terminal: New Terminal',      icon: 'console',                group: 'Terminal' },
  { id: 'problems',    label: 'View: Show Problems',         icon: 'alert-circle-outline',   group: 'View' },
  { id: 'output',      label: 'View: Show Output',          icon: 'text-box-outline',        group: 'View' },
  { id: 'toggleSidebar', label: 'View: Toggle Sidebar',     icon: 'dock-left',              group: 'View' },
  { id: 'togglePanel',   label: 'View: Toggle Panel',       icon: 'dock-bottom',            group: 'View' },
  { id: 'settings',    label: 'Preferences: Open Settings', icon: 'cog-outline',            group: 'Preferences' },
  { id: 'theme_dark',  label: 'Theme: Dark+',               icon: 'theme-light-dark',       group: 'Preferences' },
  { id: 'theme_light', label: 'Theme: Light+',              icon: 'weather-sunny',          group: 'Preferences' },
  { id: 'save',        label: 'File: Save',                 icon: 'content-save',           group: 'File' },
  { id: 'closeTab',    label: 'View: Close Editor',         icon: 'close',                  group: 'File' },
];

export default function CommandPalette({ visible, onClose, navigation }) {
  const [query, setQuery] = useState('');
  const { setSidebarTab, toggleSidebar, togglePanel, setPanelTab, activeFile, closeFile } = useStore();

  const filtered = useMemo(() => {
    if (!query.trim()) return COMMANDS;
    const q = query.toLowerCase();
    return COMMANDS.filter(c => c.label.toLowerCase().includes(q) || c.group.toLowerCase().includes(q));
  }, [query]);

  const runCommand = (cmd) => {
    onClose();
    setQuery('');
    switch (cmd.id) {
      case 'explorer':
      case 'search':
      case 'git':
      case 'extensions':
        setSidebarTab(cmd.id); break;
      case 'terminal':
        setPanelTab('terminal'); break;
      case 'problems':
        setPanelTab('problems'); break;
      case 'output':
        setPanelTab('output'); break;
      case 'toggleSidebar':
        toggleSidebar(); break;
      case 'togglePanel':
        togglePanel(); break;
      case 'settings':
        navigation?.navigate('Settings'); break;
      case 'closeTab':
        if (activeFile) closeFile(activeFile.path); break;
    }
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <TouchableOpacity style={styles.overlay} activeOpacity={1} onPress={onClose} />
      <View style={styles.palette}>
        <View style={styles.inputRow}>
          <MaterialCommunityIcons name="chevron-right" size={16} color={Colors.text_secondary} />
          <TextInput
            style={styles.input}
            value={query}
            onChangeText={setQuery}
            placeholder="Type a command..."
            placeholderTextColor={Colors.text_secondary}
            autoFocus
            autoCapitalize="none"
            autoCorrect={false}
          />
          {query.length > 0 && (
            <TouchableOpacity onPress={() => setQuery('')}>
              <MaterialCommunityIcons name="close" size={16} color={Colors.text_secondary} />
            </TouchableOpacity>
          )}
        </View>
        <ScrollView style={styles.list} keyboardShouldPersistTaps="handled">
          {filtered.map((cmd) => (
            <TouchableOpacity key={cmd.id} style={styles.item} onPress={() => runCommand(cmd)}>
              <MaterialCommunityIcons name={cmd.icon} size={16} color={Colors.text_secondary} style={styles.itemIcon} />
              <View style={{ flex: 1 }}>
                <Text style={styles.itemLabel}>{cmd.label}</Text>
              </View>
              <Text style={styles.itemGroup}>{cmd.group}</Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  palette: {
    position: 'absolute', top: 60, left: 16, right: 16,
    backgroundColor: Colors.bg_dropdown,
    borderRadius: 6, borderWidth: 1, borderColor: Colors.border,
    maxHeight: 400, elevation: 10,
  },
  inputRow: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 12, height: 44,
    borderBottomWidth: 1, borderColor: Colors.border,
  },
  input: {
    flex: 1, color: Colors.text_primary,
    fontSize: FontSizes.md, marginLeft: 6,
  },
  list: { maxHeight: 356 },
  item: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 12, paddingVertical: 10,
    borderBottomWidth: 1, borderColor: Colors.border + '30',
  },
  itemIcon: { marginRight: 10 },
  itemLabel: { color: Colors.text_primary, fontSize: FontSizes.sm },
  itemGroup: { color: Colors.text_secondary, fontSize: FontSizes.xs },
});
