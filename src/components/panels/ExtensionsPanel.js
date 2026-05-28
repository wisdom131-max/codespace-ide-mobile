import React, { useState } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, TextInput, Linking, Alert,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../../theme/colors';
import useStore from '../../hooks/useStore';

// Popular VS Code extensions info
const FEATURED_EXTENSIONS = [
  { id: 'ms-python.python', name: 'Python', publisher: 'Microsoft', description: 'Python language support', icon: 'language-python', color: '#3572A5', installed: false },
  { id: 'esbenp.prettier-vscode', name: 'Prettier', publisher: 'Prettier', description: 'Code formatter', icon: 'format-align-left', color: '#f7ba3e', installed: false },
  { id: 'dbaeumer.vscode-eslint', name: 'ESLint', publisher: 'Microsoft', description: 'JavaScript linter', icon: 'eslint', color: '#4b32c3', installed: false },
  { id: 'ms-vscode.cpptools', name: 'C/C++', publisher: 'Microsoft', description: 'C++ language support', icon: 'language-cpp', color: '#00599c', installed: false },
  { id: 'golang.go', name: 'Go', publisher: 'Google', description: 'Go language support', icon: 'language-go', color: '#00add8', installed: false },
  { id: 'rust-lang.rust-analyzer', name: 'Rust Analyzer', publisher: 'rust-lang', description: 'Rust language support', icon: 'language-rust', color: '#dea584', installed: false },
  { id: 'eamodio.gitlens', name: 'GitLens', publisher: 'GitKraken', description: 'Supercharged Git', icon: 'git', color: '#f05033', installed: false },
  { id: 'github.copilot', name: 'GitHub Copilot', publisher: 'GitHub', description: 'AI pair programmer', icon: 'robot-outline', color: '#6e40c9', installed: false },
  { id: 'ms-vscode-remote.remote-containers', name: 'Dev Containers', publisher: 'Microsoft', description: 'Container dev environments', icon: 'docker', color: '#0db7ed', installed: false },
  { id: 'ritwickdey.liveserver', name: 'Live Server', publisher: 'Ritwick Dey', description: 'Local live reload server', icon: 'web', color: '#47c5fb', installed: false },
  { id: 'ms-azuretools.vscode-docker', name: 'Docker', publisher: 'Microsoft', description: 'Docker support', icon: 'docker', color: '#0db7ed', installed: false },
  { id: 'redhat.java', name: 'Language Support for Java', publisher: 'Red Hat', description: 'Java language support', icon: 'language-java', color: '#b07219', installed: false },
];

export default function ExtensionsPanel() {
  const { activeCodespace, installedExtensions, setInstalledExtensions } = useStore();
  const [search, setSearch] = useState('');
  const [installed, setInstalled] = useState(new Set());

  const filtered = FEATURED_EXTENSIONS.filter(
    (e) =>
      e.name.toLowerCase().includes(search.toLowerCase()) ||
      e.description.toLowerCase().includes(search.toLowerCase())
  );

  const handleInstall = (ext) => {
    if (!activeCodespace) {
      Alert.alert(
        'Connect a Codespace',
        'Extensions are installed on your GitHub Codespace backend.\n\nConnect a Codespace first, then install extensions.',
        [
          { text: 'OK' },
          { text: 'Open Marketplace', onPress: () => Linking.openURL(`https://marketplace.visualstudio.com/items?itemName=${ext.id}`) },
        ]
      );
      return;
    }
    setInstalled((prev) => new Set([...prev, ext.id]));
    Alert.alert(
      'Extension Installing',
      `Installing "${ext.name}" on your Codespace...\n\nFor instant install, open your Codespace in VS Code Web and install from there.`,
      [
        { text: 'OK' },
        {
          text: 'Open Codespace',
          onPress: () => Linking.openURL(`https://${activeCodespace.name}.github.dev`),
        },
      ]
    );
  };

  const openMarketplace = () => {
    Linking.openURL('https://marketplace.visualstudio.com/vscode');
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>EXTENSIONS</Text>
        <TouchableOpacity onPress={openMarketplace}>
          <MaterialCommunityIcons name="open-in-new" size={14} color={Colors.text_secondary} />
        </TouchableOpacity>
      </View>

      <View style={styles.searchBox}>
        <MaterialCommunityIcons name="magnify" size={14} color={Colors.text_secondary} />
        <TextInput
          style={styles.searchInput}
          value={search}
          onChangeText={setSearch}
          placeholder="Search extensions..."
          placeholderTextColor={Colors.text_secondary}
        />
      </View>

      {!activeCodespace && (
        <View style={styles.infoBox}>
          <MaterialCommunityIcons name="information-outline" size={14} color={Colors.info} />
          <Text style={styles.infoText}>
            Connect a Codespace to install extensions on GitHub's servers
          </Text>
        </View>
      )}

      <ScrollView>
        {filtered.map((ext) => {
          const isInstalled = installed.has(ext.id);
          return (
            <View key={ext.id} style={styles.extItem}>
              <View style={[styles.extIcon, { backgroundColor: ext.color + '22' }]}>
                <MaterialCommunityIcons name={ext.icon} size={22} color={ext.color} />
              </View>
              <View style={styles.extInfo}>
                <Text style={styles.extName}>{ext.name}</Text>
                <Text style={styles.extPublisher}>{ext.publisher}</Text>
                <Text style={styles.extDesc} numberOfLines={2}>{ext.description}</Text>
              </View>
              <TouchableOpacity
                style={[styles.installBtn, isInstalled && styles.installedBtn]}
                onPress={() => handleInstall(ext)}
                disabled={isInstalled}
              >
                <Text style={[styles.installText, isInstalled && styles.installedText]}>
                  {isInstalled ? '✓' : 'Install'}
                </Text>
              </TouchableOpacity>
            </View>
          );
        })}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 10, paddingVertical: 6,
    borderBottomWidth: 1, borderColor: Colors.border,
  },
  headerTitle: { color: Colors.text_secondary, fontSize: 11, fontWeight: '600', letterSpacing: 1 },

  searchBox: {
    flexDirection: 'row', alignItems: 'center',
    margin: 8, paddingHorizontal: 8,
    backgroundColor: Colors.bg_input, borderRadius: 4,
    height: 28, borderWidth: 1, borderColor: Colors.border,
  },
  searchInput: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.xs, marginLeft: 4 },

  infoBox: {
    flexDirection: 'row', alignItems: 'flex-start', gap: 6,
    backgroundColor: Colors.info + '15',
    margin: 8, padding: 8, borderRadius: 4,
  },
  infoText: { flex: 1, color: Colors.info, fontSize: 11 },

  extItem: {
    flexDirection: 'row', alignItems: 'flex-start',
    paddingHorizontal: 10, paddingVertical: 8,
    borderBottomWidth: 1, borderColor: Colors.border + '30',
    gap: 8,
  },
  extIcon: { width: 36, height: 36, borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  extInfo: { flex: 1 },
  extName: { color: Colors.text_active, fontSize: FontSizes.sm, fontWeight: '500' },
  extPublisher: { color: Colors.text_secondary, fontSize: 10, marginBottom: 2 },
  extDesc: { color: Colors.text_secondary, fontSize: 11 },
  installBtn: {
    backgroundColor: Colors.bg_input, borderRadius: 4,
    paddingHorizontal: 8, paddingVertical: 4,
    borderWidth: 1, borderColor: Colors.border,
    alignSelf: 'flex-start', marginTop: 2,
  },
  installedBtn: { borderColor: Colors.success },
  installText: { color: Colors.text_primary, fontSize: 11 },
  installedText: { color: Colors.success },
});
