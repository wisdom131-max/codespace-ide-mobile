import React, { useState, useEffect } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, ActivityIndicator, Alert, Linking,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../../theme/colors';
import useStore from '../../hooks/useStore';
import { codespaceManager } from '../../services/codespace';
import githubService from '../../services/github';

export default function CodespacePanel() {
  const { activeRepo, setActiveCodespace, activeCodespace } = useStore();
  const [codespaces, setCodespaces] = useState([]);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(null);

  useEffect(() => {
    loadCodespaces();
  }, []);

  const loadCodespaces = async () => {
    setLoading(true);
    try {
      const list = await codespaceManager.listCodespaces();
      setCodespaces(list || []);
    } catch (e) {
      Alert.alert('Error', 'Could not load codespaces: ' + e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleStart = async (cs) => {
    setActionLoading(cs.name);
    try {
      const started = await codespaceManager.startCodespace(cs.name);
      setCodespaces((prev) => prev.map((c) => c.name === cs.name ? started : c));
      setActiveCodespace(started);
      Alert.alert('Codespace Started', `${cs.name} is now running.\n\nOpen in VS Code Web?`, [
        { text: 'Open VS Code Web', onPress: () => Linking.openURL(`https://${cs.name}.github.dev`) },
        { text: 'Stay in App', style: 'cancel' },
      ]);
    } catch (e) {
      Alert.alert('Error', e.message);
    } finally {
      setActionLoading(null);
    }
  };

  const handleStop = async (cs) => {
    Alert.alert('Stop Codespace', `Stop "${cs.name}"?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Stop', style: 'destructive',
        onPress: async () => {
          setActionLoading(cs.name);
          try {
            await codespaceManager.stopCodespace(cs.name);
            if (activeCodespace?.name === cs.name) setActiveCodespace(null);
            await loadCodespaces();
          } catch (e) { Alert.alert('Error', e.message); }
          finally { setActionLoading(null); }
        },
      },
    ]);
  };

  const handleOpenWeb = (cs) => {
    Linking.openURL(`https://${cs.name}.github.dev`);
  };

  const handleConnect = (cs) => {
    setActiveCodespace(cs);
    Alert.alert(
      'Codespace Connected',
      `You are now connected to "${cs.name}".\n\nThe terminal will use this codespace.`,
    );
  };

  const createNew = async () => {
    if (!activeRepo) {
      Alert.alert('Select a Repo', 'Open a repository in the Explorer first.');
      return;
    }
    const [owner, repo] = activeRepo.full_name.split('/');
    Alert.alert(
      'Create Codespace',
      `Create a new Codespace for ${activeRepo.name}?`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Create',
          onPress: async () => {
            setLoading(true);
            try {
              const cs = await githubService.createCodespace(owner, repo);
              await loadCodespaces();
              Alert.alert('Created!', `Codespace "${cs.name}" is being created. This may take a minute.`);
            } catch (e) { Alert.alert('Error', e.message); }
            finally { setLoading(false); }
          },
        },
      ]
    );
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>CODESPACES</Text>
        <View style={styles.headerActions}>
          <TouchableOpacity onPress={loadCodespaces} style={styles.iconBtn}>
            <MaterialCommunityIcons name="refresh" size={15} color={Colors.text_secondary} />
          </TouchableOpacity>
          <TouchableOpacity onPress={createNew} style={styles.iconBtn}>
            <MaterialCommunityIcons name="plus" size={15} color={Colors.text_secondary} />
          </TouchableOpacity>
        </View>
      </View>

      {activeCodespace && (
        <View style={styles.activeBar}>
          <View style={styles.activeDot} />
          <Text style={styles.activeText}>Connected: {activeCodespace.name}</Text>
          <TouchableOpacity onPress={() => setActiveCodespace(null)}>
            <MaterialCommunityIcons name="close" size={12} color={Colors.text_secondary} />
          </TouchableOpacity>
        </View>
      )}

      {loading && <ActivityIndicator style={{ marginTop: 20 }} color={Colors.accent} />}

      {!loading && codespaces.length === 0 && (
        <View style={styles.emptyState}>
          <MaterialCommunityIcons name="cloud-outline" size={40} color={Colors.text_secondary} />
          <Text style={styles.emptyTitle}>No Codespaces</Text>
          <Text style={styles.emptyText}>Create a codespace from a repository to get full VS Code power on GitHub's servers.</Text>
          <TouchableOpacity style={styles.createBtn} onPress={createNew}>
            <MaterialCommunityIcons name="plus" size={14} color="#fff" />
            <Text style={styles.createBtnText}>New Codespace</Text>
          </TouchableOpacity>
        </View>
      )}

      <ScrollView>
        {codespaces.map((cs) => {
          const statusColor = codespaceManager.getStatusColor(cs.state);
          const statusLabel = codespaceManager.getStatusLabel(cs.state);
          const isActive = activeCodespace?.name === cs.name;
          const isLoading = actionLoading === cs.name;

          return (
            <View key={cs.name} style={[styles.csCard, isActive && styles.csCardActive]}>
              <View style={styles.csHeader}>
                <View style={[styles.statusDot, { backgroundColor: statusColor }]} />
                <Text style={styles.csName} numberOfLines={1}>{cs.name}</Text>
                {isLoading && <ActivityIndicator size="small" color={Colors.accent} />}
              </View>

              <Text style={styles.csMeta}>
                {cs.repository?.full_name} · {cs.machine?.display_name || 'Standard'}
              </Text>
              <Text style={[styles.csStatus, { color: statusColor }]}>{statusLabel}</Text>

              <View style={styles.csActions}>
                {cs.state === 'Available' ? (
                  <>
                    <TouchableOpacity style={styles.actionBtn} onPress={() => handleConnect(cs)}>
                      <MaterialCommunityIcons name="link-variant" size={13} color={Colors.accent} />
                      <Text style={styles.actionBtnText}>Connect</Text>
                    </TouchableOpacity>
                    <TouchableOpacity style={styles.actionBtn} onPress={() => handleOpenWeb(cs)}>
                      <MaterialCommunityIcons name="open-in-new" size={13} color={Colors.text_secondary} />
                      <Text style={styles.actionBtnText}>Open Web</Text>
                    </TouchableOpacity>
                    <TouchableOpacity style={styles.actionBtn} onPress={() => handleStop(cs)}>
                      <MaterialCommunityIcons name="stop" size={13} color={Colors.error} />
                      <Text style={[styles.actionBtnText, { color: Colors.error }]}>Stop</Text>
                    </TouchableOpacity>
                  </>
                ) : cs.state === 'Stopped' || cs.state === 'Shutdown' ? (
                  <TouchableOpacity style={styles.actionBtn} onPress={() => handleStart(cs)}>
                    <MaterialCommunityIcons name="play" size={13} color={Colors.success} />
                    <Text style={[styles.actionBtnText, { color: Colors.success }]}>Start</Text>
                  </TouchableOpacity>
                ) : (
                  <Text style={styles.actionBtnText}>{statusLabel}</Text>
                )}
              </View>
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
  headerActions: { flexDirection: 'row' },
  iconBtn: { padding: 3 },

  activeBar: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: Colors.bg_selected,
    paddingHorizontal: 10, paddingVertical: 5,
    borderBottomWidth: 1, borderColor: Colors.border,
  },
  activeDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: Colors.success },
  activeText: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.xs },

  emptyState: { alignItems: 'center', padding: 24 },
  emptyTitle: { color: Colors.text_primary, fontSize: FontSizes.md, fontWeight: '600', marginTop: 12 },
  emptyText: { color: Colors.text_secondary, fontSize: FontSizes.xs, textAlign: 'center', marginTop: 6, marginBottom: 16 },
  createBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 4,
    backgroundColor: Colors.accent, borderRadius: 4,
    paddingHorizontal: 12, paddingVertical: 8,
  },
  createBtnText: { color: '#fff', fontSize: FontSizes.sm },

  csCard: {
    margin: 8, padding: 10,
    backgroundColor: Colors.bg_editor,
    borderRadius: 6, borderWidth: 1, borderColor: Colors.border,
  },
  csCardActive: { borderColor: Colors.accent },
  csHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 4 },
  statusDot: { width: 8, height: 8, borderRadius: 4 },
  csName: { flex: 1, color: Colors.text_active, fontSize: FontSizes.sm, fontWeight: '600' },
  csMeta: { color: Colors.text_secondary, fontSize: 11, marginBottom: 2 },
  csStatus: { fontSize: 11, marginBottom: 8 },
  csActions: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  actionBtn: { flexDirection: 'row', alignItems: 'center', gap: 3, padding: 4 },
  actionBtnText: { color: Colors.text_secondary, fontSize: 11 },
});
