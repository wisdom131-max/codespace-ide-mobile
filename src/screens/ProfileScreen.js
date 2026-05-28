import React, { useEffect, useState } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, Image, ActivityIndicator, Linking,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../theme/colors';
import useStore from '../hooks/useStore';
import githubService from '../services/github';

export default function ProfileScreen({ navigation }) {
  const { user } = useStore();
  const [profile, setProfile] = useState(null);
  const [repos, setRepos] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadProfile();
  }, []);

  const loadProfile = async () => {
    try {
      const [u, r] = await Promise.all([
        githubService.getUser(),
        githubService.getRepos(1, 6),
      ]);
      setProfile(u);
      setRepos(r);
    } catch (e) {}
    finally { setLoading(false); }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={20} color={Colors.text_secondary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Profile</Text>
      </View>

      {loading ? (
        <ActivityIndicator style={{ marginTop: 40 }} color={Colors.accent} />
      ) : (
        <ScrollView style={styles.content}>
          {/* Avatar & info */}
          <View style={styles.profileSection}>
            {profile?.avatar_url ? (
              <Image source={{ uri: profile.avatar_url }} style={styles.avatar} />
            ) : (
              <View style={[styles.avatar, styles.avatarPlaceholder]}>
                <MaterialCommunityIcons name="account" size={40} color={Colors.text_secondary} />
              </View>
            )}
            <Text style={styles.displayName}>{profile?.name || profile?.login}</Text>
            <Text style={styles.username}>@{profile?.login}</Text>
            {profile?.bio && <Text style={styles.bio}>{profile.bio}</Text>}

            <View style={styles.statsRow}>
              <View style={styles.stat}>
                <Text style={styles.statNum}>{profile?.public_repos || 0}</Text>
                <Text style={styles.statLabel}>Repos</Text>
              </View>
              <View style={styles.stat}>
                <Text style={styles.statNum}>{profile?.followers || 0}</Text>
                <Text style={styles.statLabel}>Followers</Text>
              </View>
              <View style={styles.stat}>
                <Text style={styles.statNum}>{profile?.following || 0}</Text>
                <Text style={styles.statLabel}>Following</Text>
              </View>
            </View>

            <TouchableOpacity
              style={styles.openGitHub}
              onPress={() => Linking.openURL(profile?.html_url)}
            >
              <MaterialCommunityIcons name="github" size={16} color="#fff" />
              <Text style={styles.openGitHubText}>View on GitHub</Text>
            </TouchableOpacity>
          </View>

          {/* Recent repos */}
          <Text style={styles.sectionTitle}>RECENT REPOSITORIES</Text>
          {repos.map((repo) => (
            <TouchableOpacity
              key={repo.id}
              style={styles.repoItem}
              onPress={() => {
                useStore.getState().setActiveRepo(repo);
                useStore.getState().setActiveBranch(repo.default_branch || 'main');
                navigation.goBack();
              }}
            >
              <MaterialCommunityIcons
                name={repo.private ? 'lock' : 'folder-outline'}
                size={16} color={Colors.text_secondary}
              />
              <View style={styles.repoInfo}>
                <Text style={styles.repoName}>{repo.name}</Text>
                {repo.description && (
                  <Text style={styles.repoDesc} numberOfLines={1}>{repo.description}</Text>
                )}
                <View style={styles.repoMeta}>
                  {repo.language && (
                    <Text style={styles.repoLang}>● {repo.language}</Text>
                  )}
                  <Text style={styles.repoStar}>⭐ {repo.stargazers_count}</Text>
                </View>
              </View>
              <MaterialCommunityIcons name="chevron-right" size={16} color={Colors.text_secondary} />
            </TouchableOpacity>
          ))}
          <View style={{ height: 40 }} />
        </ScrollView>
      )}
    </View>
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

  profileSection: { alignItems: 'center', padding: 24 },
  avatar: { width: 80, height: 80, borderRadius: 40, marginBottom: 12 },
  avatarPlaceholder: { backgroundColor: Colors.bg_sidebar, alignItems: 'center', justifyContent: 'center' },
  displayName: { color: Colors.text_active, fontSize: 20, fontWeight: 'bold' },
  username: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginTop: 2 },
  bio: { color: Colors.text_secondary, fontSize: FontSizes.sm, marginTop: 8, textAlign: 'center' },
  statsRow: { flexDirection: 'row', gap: 32, marginTop: 16, marginBottom: 16 },
  stat: { alignItems: 'center' },
  statNum: { color: Colors.text_active, fontSize: FontSizes.lg, fontWeight: 'bold' },
  statLabel: { color: Colors.text_secondary, fontSize: FontSizes.xs },
  openGitHub: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: Colors.bg_activitybar, borderRadius: 6,
    paddingHorizontal: 14, paddingVertical: 8,
  },
  openGitHubText: { color: '#fff', fontSize: FontSizes.sm },

  sectionTitle: {
    color: Colors.text_secondary, fontSize: 11, fontWeight: '600',
    letterSpacing: 1, paddingHorizontal: 16, paddingVertical: 8,
    borderTopWidth: 1, borderColor: Colors.border,
  },
  repoItem: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    paddingHorizontal: 16, paddingVertical: 10,
    borderBottomWidth: 1, borderColor: Colors.border + '40',
  },
  repoInfo: { flex: 1 },
  repoName: { color: Colors.text_primary, fontSize: FontSizes.sm, fontWeight: '500' },
  repoDesc: { color: Colors.text_secondary, fontSize: FontSizes.xs, marginTop: 1 },
  repoMeta: { flexDirection: 'row', gap: 10, marginTop: 2 },
  repoLang: { color: Colors.accent, fontSize: 11 },
  repoStar: { color: Colors.text_secondary, fontSize: 11 },
});
