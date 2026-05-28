import React from 'react';
import { View, Text, TouchableOpacity, ScrollView, StyleSheet } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes } from '../theme/colors';
import useStore from '../hooks/useStore';

const FILE_ICONS = {
  js: { icon: 'language-javascript', color: '#f0db4f' },
  jsx: { icon: 'react', color: '#61dafb' },
  ts: { icon: 'language-typescript', color: '#3178c6' },
  tsx: { icon: 'react', color: '#3178c6' },
  py: { icon: 'language-python', color: '#3572A5' },
  json: { icon: 'code-json', color: '#cbcb41' },
  md: { icon: 'language-markdown', color: '#cccccc' },
  html: { icon: 'language-html5', color: '#e34c26' },
  css: { icon: 'language-css3', color: '#563d7c' },
  scss: { icon: 'sass', color: '#c6538c' },
  go: { icon: 'language-go', color: '#00add8' },
  rs: { icon: 'language-rust', color: '#dea584' },
  java: { icon: 'language-java', color: '#b07219' },
  kt: { icon: 'language-kotlin', color: '#A97BFF' },
  swift: { icon: 'language-swift', color: '#FA7343' },
  dart: { icon: 'dart', color: '#00B4AB' },
  rb: { icon: 'language-ruby', color: '#CC342D' },
  php: { icon: 'language-php', color: '#4F5D95' },
  sh: { icon: 'bash', color: '#89e051' },
  yaml: { icon: 'file-code', color: '#cb171e' },
  yml: { icon: 'file-code', color: '#cb171e' },
  vue: { icon: 'vuejs', color: '#41b883' },
  default: { icon: 'file-outline', color: Colors.text_secondary },
};

function getFileIcon(filename) {
  const ext = filename?.split('.').pop()?.toLowerCase();
  return FILE_ICONS[ext] || FILE_ICONS.default;
}

export default function TabBar() {
  const { openFiles, activeFile, setActiveFile, closeFile, fileContents } = useStore();

  return (
    <ScrollView
      horizontal
      style={styles.container}
      showsHorizontalScrollIndicator={false}
    >
      {openFiles.map((file) => {
        const isActive = activeFile?.path === file.path;
        const isDirty = fileContents[file.path]?.isDirty;
        const { icon, color } = getFileIcon(file.name);

        return (
          <TouchableOpacity
            key={file.path}
            style={[styles.tab, isActive && styles.tabActive]}
            onPress={() => setActiveFile(file)}
          >
            <MaterialCommunityIcons name={icon} size={14} color={color} style={{ marginRight: 4 }} />
            <Text style={[styles.tabText, isActive && styles.tabTextActive]} numberOfLines={1}>
              {file.name}
            </Text>
            {isDirty && <View style={styles.dirtyDot} />}
            <TouchableOpacity
              style={styles.closeBtn}
              onPress={(e) => { e.stopPropagation?.(); closeFile(file.path); }}
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            >
              <MaterialCommunityIcons name="close" size={12} color={Colors.text_secondary} />
            </TouchableOpacity>
          </TouchableOpacity>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    height: 35,
    backgroundColor: Colors.bg_titlebar,
    borderBottomWidth: 1,
    borderColor: Colors.border,
  },
  tab: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 10, height: 35,
    backgroundColor: Colors.bg_tab_inactive,
    borderRightWidth: 1, borderColor: Colors.border,
    minWidth: 80, maxWidth: 160,
  },
  tabActive: {
    backgroundColor: Colors.bg_tab_active,
    borderTopWidth: 1, borderTopColor: Colors.accent,
  },
  tabText: {
    color: Colors.text_secondary, fontSize: FontSizes.xs,
    flex: 1, marginRight: 4,
  },
  tabTextActive: { color: Colors.text_active },
  dirtyDot: {
    width: 6, height: 6, borderRadius: 3,
    backgroundColor: Colors.text_secondary, marginRight: 4,
  },
  closeBtn: {
    width: 16, height: 16, alignItems: 'center', justifyContent: 'center',
  },
});
