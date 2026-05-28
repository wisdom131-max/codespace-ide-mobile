import React, { useState, useEffect } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, StyleSheet,
  ActivityIndicator, Alert, ScrollView, Image, Linking,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../theme/colors';
import AuthService from '../services/auth';
import useStore from '../hooks/useStore';

export default function LoginScreen({ navigation }) {
  const [mode, setMode] = useState('choose'); // 'choose' | 'pat' | 'device'
  const [pat, setPat] = useState('');
  const [loading, setLoading] = useState(false);
  const [deviceData, setDeviceData] = useState(null);
  const [pollInterval, setPollInterval] = useState(null);
  const [countdown, setCountdown] = useState(0);
  const { setUser } = useStore();

  useEffect(() => {
    return () => {
      if (pollInterval) clearInterval(pollInterval);
    };
  }, [pollInterval]);

  const handlePATLogin = async () => {
    if (!pat.trim()) {
      Alert.alert('Error', 'Please enter your GitHub Personal Access Token');
      return;
    }
    setLoading(true);
    try {
      const result = await AuthService.signInWithPAT(pat.trim());
      if (result.success) {
        setUser(result.user);
        navigation.replace('Main');
      }
    } catch (err) {
      Alert.alert('Login Failed', err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleDeviceFlow = async () => {
    setLoading(true);
    try {
      const data = await AuthService.signInWithDeviceFlow();
      setDeviceData(data);
      setMode('device');
      setCountdown(data.expires_in);

      // Poll for token
      let interval = data.interval || 5;
      const poll = setInterval(async () => {
        const result = await AuthService.pollForToken(data.device_code, interval);
        if (result.success) {
          clearInterval(poll);
          const user = await AuthService.getSavedUser() || { login: 'User' };
          setUser(user);
          navigation.replace('Main');
        } else if (result.slowDown) {
          interval += 5;
        } else if (result.error && result.error !== 'authorization_pending') {
          clearInterval(poll);
          Alert.alert('Auth Error', result.error);
          setMode('choose');
        }
      }, interval * 1000);

      setPollInterval(poll);
    } catch (err) {
      Alert.alert('Error', err.message);
    } finally {
      setLoading(false);
    }
  };

  const openGitHubAuth = () => {
    if (deviceData?.verification_uri) {
      Linking.openURL(deviceData.verification_uri);
    }
  };

  const createPATLink = () => {
    Linking.openURL('https://github.com/settings/tokens/new?scopes=repo,codespace,user,gist,workflow&description=CodeSpace+IDE+Mobile');
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Logo */}
      <View style={styles.logoArea}>
        <View style={styles.logoBox}>
          <MaterialCommunityIcons name="microsoft-visual-studio-code" size={72} color={Colors.accent} />
        </View>
        <Text style={styles.appName}>CodeSpace IDE</Text>
        <Text style={styles.tagline}>VS Code on your phone, powered by GitHub</Text>
      </View>

      {mode === 'choose' && (
        <View style={styles.chooseArea}>
          <Text style={styles.sectionTitle}>Connect to GitHub</Text>

          {/* Device Flow */}
          <TouchableOpacity style={styles.primaryBtn} onPress={handleDeviceFlow} disabled={loading}>
            {loading ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <>
                <MaterialCommunityIcons name="github" size={22} color="#fff" style={styles.btnIcon} />
                <Text style={styles.primaryBtnText}>Sign in with GitHub</Text>
              </>
            )}
          </TouchableOpacity>

          <View style={styles.divider}>
            <View style={styles.dividerLine} />
            <Text style={styles.dividerText}>or</Text>
            <View style={styles.dividerLine} />
          </View>

          {/* PAT */}
          <TouchableOpacity style={styles.secondaryBtn} onPress={() => setMode('pat')}>
            <MaterialCommunityIcons name="key-variant" size={18} color={Colors.text_primary} style={styles.btnIcon} />
            <Text style={styles.secondaryBtnText}>Use Personal Access Token</Text>
          </TouchableOpacity>

          <View style={styles.featureList}>
            {[
              'Full VS Code editor on any Android device',
              'Connect to GitHub Codespaces',
              'Edit, commit, push directly from phone',
              'Integrated terminal via Codespace',
              'Extensions via Codespace backend',
              'AI assistant powered by Copilot',
            ].map((f, i) => (
              <View key={i} style={styles.featureItem}>
                <MaterialCommunityIcons name="check-circle" size={16} color={Colors.success} />
                <Text style={styles.featureText}>{f}</Text>
              </View>
            ))}
          </View>
        </View>
      )}

      {mode === 'pat' && (
        <View style={styles.patArea}>
          <TouchableOpacity onPress={() => setMode('choose')} style={styles.backBtn}>
            <MaterialCommunityIcons name="arrow-left" size={20} color={Colors.text_secondary} />
            <Text style={styles.backText}>Back</Text>
          </TouchableOpacity>

          <Text style={styles.sectionTitle}>Personal Access Token</Text>
          <Text style={styles.hint}>
            Generate a token with: <Text style={styles.scopeText}>repo, codespace, user, gist, workflow</Text> scopes
          </Text>

          <TouchableOpacity onPress={createPATLink} style={styles.linkBtn}>
            <MaterialCommunityIcons name="open-in-new" size={14} color={Colors.accent} />
            <Text style={styles.linkText}> Create token on GitHub.com</Text>
          </TouchableOpacity>

          <TextInput
            style={styles.tokenInput}
            value={pat}
            onChangeText={setPat}
            placeholder="ghp_xxxxxxxxxxxxxxxxxxxx"
            placeholderTextColor={Colors.text_secondary}
            secureTextEntry
            autoCapitalize="none"
            autoCorrect={false}
          />

          <TouchableOpacity style={styles.primaryBtn} onPress={handlePATLogin} disabled={loading}>
            {loading ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.primaryBtnText}>Connect</Text>
            )}
          </TouchableOpacity>
        </View>
      )}

      {mode === 'device' && deviceData && (
        <View style={styles.deviceArea}>
          <Text style={styles.sectionTitle}>Authenticate on GitHub</Text>
          <Text style={styles.deviceHint}>
            1. Open <Text style={styles.linkText}>github.com/login/device</Text> on any browser
          </Text>
          <Text style={styles.deviceHint}>2. Enter this code:</Text>

          <TouchableOpacity onPress={openGitHubAuth} style={styles.codeBox}>
            <Text style={styles.deviceCode}>{deviceData.user_code}</Text>
            <Text style={styles.tapToOpen}>Tap to open GitHub</Text>
          </TouchableOpacity>

          <View style={styles.waitingArea}>
            <ActivityIndicator color={Colors.accent} size="small" />
            <Text style={styles.waitingText}>Waiting for authorization...</Text>
          </View>

          <TouchableOpacity onPress={() => { if (pollInterval) clearInterval(pollInterval); setMode('choose'); }} style={styles.cancelBtn}>
            <Text style={styles.cancelText}>Cancel</Text>
          </TouchableOpacity>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: Colors.bg_editor },
  content: { padding: Spacing.xl, paddingTop: 60, alignItems: 'center' },
  logoArea: { alignItems: 'center', marginBottom: Spacing.xxl },
  logoBox: {
    width: 100, height: 100, borderRadius: 20,
    backgroundColor: Colors.bg_sidebar, alignItems: 'center', justifyContent: 'center',
    marginBottom: Spacing.md,
  },
  appName: { fontSize: 28, fontWeight: 'bold', color: Colors.text_active, marginBottom: 6 },
  tagline: { fontSize: FontSizes.sm, color: Colors.text_secondary, textAlign: 'center' },

  chooseArea: { width: '100%' },
  patArea: { width: '100%' },
  deviceArea: { width: '100%', alignItems: 'center' },

  sectionTitle: { fontSize: FontSizes.lg, fontWeight: '600', color: Colors.text_active, marginBottom: Spacing.md },
  hint: { fontSize: FontSizes.sm, color: Colors.text_secondary, marginBottom: Spacing.xs },
  scopeText: { color: Colors.syntax_string, fontFamily: 'monospace' },

  primaryBtn: {
    backgroundColor: Colors.accent,
    borderRadius: 6, padding: Spacing.md,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    marginBottom: Spacing.md,
  },
  primaryBtnText: { color: '#fff', fontSize: FontSizes.md, fontWeight: '600' },
  btnIcon: { marginRight: Spacing.sm },

  secondaryBtn: {
    backgroundColor: Colors.bg_sidebar,
    borderRadius: 6, padding: Spacing.md,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
    borderWidth: 1, borderColor: Colors.border,
    marginBottom: Spacing.xl,
  },
  secondaryBtnText: { color: Colors.text_primary, fontSize: FontSizes.md },

  divider: { flexDirection: 'row', alignItems: 'center', marginBottom: Spacing.md },
  dividerLine: { flex: 1, height: 1, backgroundColor: Colors.border },
  dividerText: { color: Colors.text_secondary, marginHorizontal: Spacing.sm, fontSize: FontSizes.sm },

  featureList: { marginTop: Spacing.md },
  featureItem: { flexDirection: 'row', alignItems: 'center', marginBottom: Spacing.sm },
  featureText: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginLeft: Spacing.sm },

  backBtn: { flexDirection: 'row', alignItems: 'center', marginBottom: Spacing.md },
  backText: { color: Colors.text_secondary, marginLeft: 4 },

  linkBtn: { flexDirection: 'row', alignItems: 'center', marginBottom: Spacing.md },
  linkText: { color: Colors.accent, fontSize: FontSizes.sm },

  tokenInput: {
    backgroundColor: Colors.bg_input, borderRadius: 6, padding: Spacing.md,
    color: Colors.text_active, fontSize: FontSizes.sm, fontFamily: 'monospace',
    borderWidth: 1, borderColor: Colors.border, marginBottom: Spacing.md,
    width: '100%',
  },

  codeBox: {
    backgroundColor: Colors.bg_sidebar, borderRadius: 8, padding: Spacing.xl,
    alignItems: 'center', borderWidth: 2, borderColor: Colors.accent,
    marginVertical: Spacing.lg, width: '100%',
  },
  deviceCode: { fontSize: 32, fontWeight: 'bold', color: Colors.text_active, letterSpacing: 8, fontFamily: 'monospace' },
  tapToOpen: { color: Colors.text_link, fontSize: FontSizes.sm, marginTop: Spacing.sm },
  deviceHint: { color: Colors.text_secondary, fontSize: FontSizes.md, marginBottom: Spacing.sm, textAlign: 'center' },

  waitingArea: { flexDirection: 'row', alignItems: 'center', marginVertical: Spacing.md },
  waitingText: { color: Colors.text_secondary, marginLeft: Spacing.sm },

  cancelBtn: { marginTop: Spacing.md },
  cancelText: { color: Colors.error },
});
