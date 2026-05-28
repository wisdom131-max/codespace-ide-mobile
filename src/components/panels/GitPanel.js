import React, { useState, useEffect } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, ActivityIndicator, TextInput, Alert,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../../theme/colors';
import useStore from '../../hooks/useStore';
import githubService from '../../services/github';

export default function GitPanel() {
  const { activeRepo, activeBranch, fileContents, setActiveBranch } = useStore();
  const [branches, setBranches] = useState([]);
  const [commits, setCommits] = useState([]);
  const [prs, setPRs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [tab, setTab] = useState('changes'); // changes | branches | commits | prs
  const [commitMsg, setCommitMsg] = useState('');

  useEffect(() => {
    if (activeRepo) loadData();
  }, [activeRepo, activeBranch]);

  const loadData = async () => {
    if (!activeRepo) return;
    const [owner, repo] = activeRepo.full_name.split('/');
    setLoading(true);
    try {
      const [b, c, p] = await Promise.all([
        githubService.getBranches(owner, repo),
        githubService.getCommits(owner, repo, activeBranch),
        githubService.getPullRequests(owner, repo),
      ]);
      setBranches(b);
      setCommits(c);
      setPRs(p);
    } catch (e) {}
    finally { setLoading(false); }
  };

  const dirtyFiles = Object.entries(fileContents)
    .filter(([_, v]) => v.isDirty)
    .map(([path]) => path);

  const switchBranch = (branchName) => {
    setActiveBranch(branchName);
    useStore.getState().setActiveBranch(branchName);
  };

  const TABS = ['changes', 'branches', 'commits', 'prs'];

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>SOURCE CONTROL</Text>
        <TouchableOpacity onPress={loadData}>
          <MaterialCommunityIcons name="refresh" size={15} color={Colors.text_secondary} />
        </TouchableOpacity>
      </View>

      {/* Branch indicator */}
      <View style={styles.branchBar}>
        <MaterialCommunityIcons name="source-branch" size={14} color={Colors.accent} />
        <Text style={styles.branchName}>{activeBranch}</Text>
      </View>

      {/* Sub tabs */}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.tabRow}>
        {TABS.map((t) => (
          <TouchableOpacity
            key={t}
            style={[styles.subTab, tab === t && styles.subTabActive]}
            onPress={() => setTab(t)}
          >
            <Text style={[styles.subTabText, tab === t && styles.subTabTextActive]}>
              {t === 'prs' ? 'Pull Requests' : t.charAt(0).toUpperCase() + t.slice(1)}
              {t === 'changes' && dirtyFiles.length > 0 ? ` (${dirtyFiles.length})` : ''}
              {t === 'prs' && prs.length > 0 ? ` (${prs.length})` : ''}
            </Text>
          </TouchableOpacity>
        ))}
      </ScrollView>

      {loading && <ActivityIndicator style={{ marginTop: 16 }} color={Colors.accent} />}

      {!loading && (
        <ScrollView style={{ flex: 1 }}>
          {/* Changes */}
          {tab === 'changes' && (
            <View>
              {dirtyFiles.length === 0 ? (
                <View style={styles.emptyState}>
                  <MaterialCommunityIcons name="check-circle-outline" size={28} color={Colors.text_secondary} />
                  <Text style={styles.emptyText}>No unsaved changes</Text>
                </View>
              ) : (
                <>
                  {dirtyFiles.map((path) => (
                    <View key={path} style={styles.changeItem}>
                      <MaterialCommunityIcons name="circle-medium" size={16} color={Colors.git_modified} />
                      <Text style={styles.changePath} numberOfLines={1}>{path}</Text>
                      <Text style={styles.changeType}>M</Text>
                    </View>
                  ))}
                  <View style={styles.commitArea}>
                    <TextInput
                      style={styles.commitInput}
                      value={commitMsg}
                      onChangeText={setCommitMsg}
                      placeholder="Commit message..."
                      placeholderTextColor={Colors.text_secondary}
                      multiline
                    />
                    <TouchableOpacity
                      style={[styles.commitBtn, !commitMsg.trim() && styles.commitBtnDisabled]}
                      disabled={!commitMsg.trim()}
                      onPress={() => Alert.alert('Commit', 'Save files first then commit from the editor toolbar.')}
                    >
                      <MaterialCommunityIcons name="check" size={14} color="#fff" />
                      <Text style={styles.commitBtnText}>Commit</Text>
                    </TouchableOpacity>
                  </View>
                </>
              )}
            </View>
          )}

          {/* Branches */}
          {tab === 'branches' && (
            <View>
              {branches.map((b) => (
                <TouchableOpacity
                  key={b.name}
                  style={[styles.branchItem, b.name === activeBranch && styles.branchItemActive]}
                  onPress={() => switchBranch(b.name)}
                >
                  <MaterialCommunityIcons
                    name={b.name === activeBranch ? 'source-branch-check' : 'source-branch'}
                    size={14}
                    color={b.name === activeBranch ? Colors.accent : Colors.text_secondary}
                  />
                  <Text style={[styles.branchItemText, b.name === activeBranch && { color: Colors.accent }]}>
                    {b.name}
                  </Text>
                  {b.protected && (
                    <MaterialCommunityIcons name="shield-outline" size={12} color={Colors.warning} />
                  )}
                </TouchableOpacity>
              ))}
            </View>
          )}

          {/* Commits */}
          {tab === 'commits' && (
            <View>
              {commits.map((c) => (
                <View key={c.sha} style={styles.commitItem}>
                  <Text style={styles.commitSha}>{c.sha.slice(0, 7)}</Text>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.commitMessage} numberOfLines={2}>{c.commit.message}</Text>
                    <Text style={styles.commitMeta}>
                      {c.commit.author.name} · {new Date(c.commit.author.date).toLocaleDateString()}
                    </Text>
                  </View>
                </View>
              ))}
            </View>
          )}

          {/* PRs */}
          {tab === 'prs' && (
            <View>
              {prs.length === 0 ? (
                <View style={styles.emptyState}>
                  <MaterialCommunityIcons name="source-pull" size={28} color={Colors.text_secondary} />
                  <Text style={styles.emptyText}>No open pull requests</Text>
                </View>
              ) : (
                prs.map((pr) => (
                  <View key={pr.id} style={styles.prItem}>
                    <View style={styles.prHeader}>
                      <MaterialCommunityIcons name="source-pull" size={14} color={Colors.success} />
                      <Text style={styles.prNumber}>#{pr.number}</Text>
                      <Text style={styles.prTitle} numberOfLines={1}>{pr.title}</Text>
                    </View>
                    <Text style={styles.prMeta}>
                      {pr.head.ref} → {pr.base.ref} · {pr.user.login}
                    </Text>
                  </View>
                ))
              )}
            </View>
          )}
        </ScrollView>
      )}
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
  branchBar: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 10, paddingVertical: 5,
    borderBottomWidth: 1, borderColor: Colors.border,
  },
  branchName: { color: Colors.accent, fontSize: FontSizes.xs, marginLeft: 5 },
  tabRow: { borderBottomWidth: 1, borderColor: Colors.border, maxHeight: 30 },
  subTab: { paddingHorizontal: 10, height: 30, justifyContent: 'center' },
  subTabActive: { borderBottomWidth: 2, borderBottomColor: Colors.accent },
  subTabText: { color: Colors.text_secondary, fontSize: FontSizes.xs },
  subTabTextActive: { color: Colors.text_active },

  emptyState: { alignItems: 'center', padding: 24 },
  emptyText: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginTop: 8 },

  changeItem: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 12, paddingVertical: 6,
  },
  changePath: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.xs, marginLeft: 4 },
  changeType: { color: Colors.git_modified, fontSize: FontSizes.xs, fontWeight: 'bold', marginLeft: 4 },

  commitArea: { padding: 10 },
  commitInput: {
    backgroundColor: Colors.bg_input, borderRadius: 4, padding: 8,
    color: Colors.text_primary, fontSize: FontSizes.xs,
    borderWidth: 1, borderColor: Colors.border,
    minHeight: 60, textAlignVertical: 'top', marginBottom: 8,
  },
  commitBtn: {
    backgroundColor: Colors.accent, borderRadius: 4, padding: 8,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center',
  },
  commitBtnDisabled: { backgroundColor: Colors.bg_hover },
  commitBtnText: { color: '#fff', fontSize: FontSizes.xs, marginLeft: 4 },

  branchItem: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    paddingHorizontal: 12, paddingVertical: 8,
    borderBottomWidth: 1, borderColor: Colors.border + '30',
  },
  branchItemActive: { backgroundColor: Colors.bg_hover },
  branchItemText: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.sm },

  commitItem: {
    flexDirection: 'row', gap: 8,
    paddingHorizontal: 12, paddingVertical: 8,
    borderBottomWidth: 1, borderColor: Colors.border + '30',
  },
  commitSha: { color: Colors.accent, fontSize: 11, fontFamily: 'monospace', width: 44 },
  commitMessage: { color: Colors.text_primary, fontSize: FontSizes.xs },
  commitMeta: { color: Colors.text_secondary, fontSize: 10, marginTop: 2 },

  prItem: {
    paddingHorizontal: 12, paddingVertical: 8,
    borderBottomWidth: 1, borderColor: Colors.border + '30',
  },
  prHeader: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  prNumber: { color: Colors.success, fontSize: FontSizes.xs },
  prTitle: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.xs },
  prMeta: { color: Colors.text_secondary, fontSize: 10, marginTop: 2 },
});
