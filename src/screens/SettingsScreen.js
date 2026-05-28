import React, { useState } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, Switch, Alert, Linking,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../theme/colors';
import useStore from '../hooks/useStore';
import AuthService from '../services/auth';

export default function SettingsScreen({ navigation }) {
  const { fontSize, setFontSize, theme, setTheme } = useStore();
  const [wordWrap, setWordWrap] = useState(false);
  const [minimap, setMinimap] = useState(false);
  const [lineNumbers, setLineNumbers] = useState(true);
  const [autoSave, setAutoSave] = useState(false);

  const handleSignOut = () => {
    Alert.alert('Sign Out', 'Are you sure you want to sign out?', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Sign Out', style: 'destructive',
        onPress: async () => {
          await AuthService.signOut();
          navigation.replace('Login');
        },
      },
    ]);
  };

  const fontSizes = [11, 12, 13, 14, 15, 16, 18, 20];

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={20} color={Colors.text_secondary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Settings</Text>
      </View>

      <ScrollView style={styles.content}>
        {/* Editor */}
        <Text style={styles.sectionTitle}>Editor</Text>

        <View style={styles.settingRow}>
          <Text style={styles.settingLabel}>Font Size</Text>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.fontSizeRow}>
            {fontSizes.map((s) => (
              <TouchableOpacity
                key={s}
                style={[styles.fontSizeBtn, fontSize === s && styles.fontSizeBtnActive]}
                onPress={() => setFontSize(s)}
              >
                <Text style={[styles.fontSizeBtnText, fontSize === s && styles.fontSizeBtnTextActive]}>
                  {s}
                </Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
        </View>

        <ToggleSetting label="Word Wrap" value={wordWrap} onChange={setWordWrap} />
        <ToggleSetting label="Line Numbers" value={lineNumbers} onChange={setLineNumbers} />
        <ToggleSetting label="Auto Save" value={autoSave} onChange={setAutoSave} />
        <ToggleSetting label="Minimap" value={minimap} onChange={setMinimap} />

        {/* Theme */}
        <Text style={styles.sectionTitle}>Appearance</Text>
        {['dark', 'light', 'highContrast'].map((t) => (
          <TouchableOpacity
            key={t}
            style={[styles.themeItem, theme === t && styles.themeItemActive]}
            onPress={() => setTheme(t)}
          >
            <MaterialCommunityIcons
              name={theme === t ? 'radiobox-marked' : 'radiobox-blank'}
              size={18}
              color={theme === t ? Colors.accent : Colors.text_secondary}
            />
            <Text style={[styles.themeItemText, theme === t && { color: Colors.accent }]}>
              {{ dark: 'Dark+ (default dark)', light: 'Light+ (default light)', highContrast: 'High Contrast' }[t]}
            </Text>
          </TouchableOpacity>
        ))}

        {/* GitHub */}
        <Text style={styles.sectionTitle}>GitHub</Text>
        <LinkSetting
          label="GitHub Settings"
          icon="github"
          onPress={() => Linking.openURL('https://github.com/settings')}
        />
        <LinkSetting
          label="Manage Codespaces"
          icon="cloud-outline"
          onPress={() => Linking.openURL('https://github.com/codespaces')}
        />
        <LinkSetting
          label="Personal Access Tokens"
          icon="key-variant"
          onPress={() => Linking.openURL('https://github.com/settings/tokens')}
        />
        <LinkSetting
          label="VS Code Marketplace"
          icon="puzzle-outline"
          onPress={() => Linking.openURL('https://marketplace.visualstudio.com/vscode')}
        />

        {/* About */}
        <Text style={styles.sectionTitle}>About</Text>
        <View style={styles.aboutCard}>
          <Text style={styles.aboutName}>CodeSpace IDE</Text>
          <Text style={styles.aboutVersion}>Version 1.0.0</Text>
          <Text style={styles.aboutDesc}>VS Code for Android, powered by GitHub Codespaces</Text>
        </View>

        {/* Sign Out */}
        <TouchableOpacity style={styles.signOutBtn} onPress={handleSignOut}>
          <MaterialCommunityIcons name="logout" size={18} color={Colors.error} />
          <Text style={styles.signOutText}>Sign Out</Text>
        </TouchableOpacity>

        <View style={{ height: 40 }} />
      </ScrollView>
    </View>
  );
}

function ToggleSetting({ label, value, onChange }) {
  return (
    <View style={styles.settingRow}>
      <Text style={styles.settingLabel}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: Colors.border, true: Colors.accent }}
        thumbColor={value ? '#fff' : Colors.text_secondary}
      />
    </View>
  );
}

function LinkSetting({ label, icon, onPress }) {
  return (
    <TouchableOpacity style={styles.settingRow} onPress={onPress}>
      <MaterialCommunityIcons name={icon} size={16} color={Colors.text_secondary} style={{ marginRight: 8 }} />
      <Text style={[styles.settingLabel, { flex: 1 }]}>{label}</Text>
      <MaterialCommunityIcons name="open-in-new" size={14} color={Colors.text_secondary} />
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: Colors.bg_editor },
  header: {
    flexDirection: 'row', alignItems: 'center',
    paddingTop: 50, paddingHorizontal: 16, paddingBottom: 12,
    borderBottomWidth: 1, borderColor: Colors.border,
    backgroundColor: Colors.bg_titlebar,
  },
  backBtn: { marginRight: 12 },
  headerTitle: { color: Colors.text_active, fontSize: FontSizes.lg, fontWeight: '600' },
  content: { flex: 1 },
  sectionTitle: {
    color: Colors.text_secondary, fontSize: 11, fontWeight: '600',
    letterSpacing: 1, paddingHorizontal: 16, paddingTop: 20, paddingBottom: 6,
    borderBottomWidth: 1, borderColor: Colors.border,
  },
  settingRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 12,
    borderBottomWidth: 1, borderColor: Colors.border + '40',
  },
  settingLabel: { color: Colors.text_primary, fontSize: FontSizes.md },
  fontSizeRow: { flexGrow: 0 },
  fontSizeBtn: {
    paddingHorizontal: 8, paddingVertical: 4,
    borderRadius: 4, borderWidth: 1, borderColor: Colors.border,
    marginLeft: 4,
  },
  fontSizeBtnActive: { backgroundColor: Colors.accent, borderColor: Colors.accent },
  fontSizeBtnText: { color: Colors.text_secondary, fontSize: 12 },
  fontSizeBtnTextActive: { color: '#fff' },
  themeItem: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    paddingHorizontal: 16, paddingVertical: 12,
    borderBottomWidth: 1, borderColor: Colors.border + '40',
  },
  themeItemActive: { backgroundColor: Colors.bg_hover },
  themeItemText: { color: Colors.text_primary, fontSize: FontSizes.md },
  aboutCard: {
    margin: 16, padding: 16,
    backgroundColor: Colors.bg_sidebar,
    borderRadius: 8, borderWidth: 1, borderColor: Colors.border,
  },
  aboutName: { color: Colors.text_active, fontSize: FontSizes.lg, fontWeight: 'bold' },
  aboutVersion: { color: Colors.accent, fontSize: FontSizes.sm, marginTop: 2 },
  aboutDesc: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginTop: 4 },
  signOutBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    margin: 16, padding: 14,
    borderRadius: 8, borderWidth: 1, borderColor: Colors.error + '40',
    backgroundColor: Colors.error + '10',
  },
  signOutText: { color: Colors.error, fontSize: FontSizes.md },
});
