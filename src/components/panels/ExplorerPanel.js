import React, { useState, useEffect } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, ActivityIndicator, Alert, TextInput,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../../theme/colors';
import useStore from '../../hooks/useStore';
import githubService from '../../services/github';

const FILE_ICONS = {
  js:'language-javascript', jsx:'react', ts:'language-typescript', tsx:'react',
  py:'language-python', json:'code-json', md:'language-markdown',
  html:'language-html5', css:'language-css3', go:'language-go',
  rs:'language-rust', java:'language-java', kt:'language-kotlin',
  dart:'dart', vue:'vuejs', sh:'bash', yml:'file-code', yaml:'file-code',
  png:'file-image', jpg:'file-image', svg:'file-image', gif:'file-image',
  txt:'file-document-outline',
};
const FILE_COLORS = {
  js:'#f0db4f', jsx:'#61dafb', ts:'#3178c6', tsx:'#3178c6',
  py:'#3572A5', json:'#cbcb41', md:'#cccccc', html:'#e34c26',
  css:'#563d7c', go:'#00add8', rs:'#dea584', java:'#b07219',
  kt:'#A97BFF', dart:'#00B4AB', vue:'#41b883',
};

function getIcon(item) {
  if (item.type === 'dir') return { icon: 'folder', color: '#dcb67a' };
  const ext = item.name.split('.').pop().toLowerCase();
  return { icon: FILE_ICONS[ext] || 'file-outline', color: FILE_COLORS[ext] || Colors.text_secondary };
}

function FileTreeItem({ item, depth, repoInfo, onFileOpen }) {
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState([]);
  const [loading, setLoading] = useState(false);
  const { icon, color } = getIcon(item);

  const handlePress = async () => {
    if (item.type === 'dir') {
      if (!expanded && children.length === 0) {
        setLoading(true);
        try {
          const contents = await githubService.getContents(repoInfo.owner, repoInfo.repo, item.path);
          setChildren(Array.isArray(contents) ? contents.sort((a,b) =>
            a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1
          ) : []);
        } catch (e) {
          Alert.alert('Error', e.message);
        } finally {
          setLoading(false);
        }
      }
      setExpanded(!expanded);
    } else {
      onFileOpen(item);
    }
  };

  return (
    <View>
      <TouchableOpacity
        style={[styles.treeItem, { paddingLeft: 12 + depth * 12 }]}
        onPress={handlePress}
      >
        {item.type === 'dir' && (
          <MaterialCommunityIcons
            name={expanded ? 'chevron-down' : 'chevron-right'}
            size={14} color={Colors.text_secondary} style={{ marginRight: 2 }}
          />
        )}
        {item.type !== 'dir' && <View style={{ width: 16 }} />}
        <MaterialCommunityIcons name={icon} size={15} color={color} style={{ marginRight: 5 }} />
        <Text style={styles.itemName} numberOfLines={1}>{item.name}</Text>
        {loading && <ActivityIndicator size="small" color={Colors.text_secondary} style={{ marginLeft: 4 }} />}
      </TouchableOpacity>

      {expanded && children.map((child) => (
        <FileTreeItem
          key={child.path}
          item={child}
          depth={depth + 1}
          repoInfo={repoInfo}
          onFileOpen={onFileOpen}
        />
      ))}
    </View>
  );
}

export default function ExplorerPanel({ navigation }) {
  const { activeRepo, activeBranch, openFile, setFileContent, user } = useStore();
  const [rootItems, setRootItems] = useState([]);
  const [loading, setLoading]     = useState(false);
  const [repos, setRepos]         = useState([]);
  const [showRepos, setShowRepos] = useState(!activeRepo);
  const [repoSearch, setRepoSearch] = useState('');

  useEffect(() => {
    if (activeRepo) loadRoot();
    else loadRepos();
  }, [activeRepo, activeBranch]);

  const loadRepos = async () => {
    setLoading(true);
    try {
      const data = await githubService.getRepos();
      setRepos(data);
    } catch (e) { Alert.alert('Error', e.message); }
    finally { setLoading(false); }
  };

  const loadRoot = async () => {
    if (!activeRepo) return;
    setLoading(true);
    try {
      const [owner, repo] = activeRepo.full_name.split('/');
      const contents = await githubService.getContents(owner, repo, '', activeBranch);
      setRootItems(Array.isArray(contents) ? contents.sort((a,b) =>
        a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1
      ) : []);
      setShowRepos(false);
    } catch (e) { Alert.alert('Error loading repo', e.message); }
    finally { setLoading(false); }
  };

  const handleFileOpen = async (item) => {
    if (!activeRepo) return;
    const [owner, repo] = activeRepo.full_name.split('/');
    try {
      const fileData = await githubService.getFileContent(owner, repo, item.path);
      openFile({ name: item.name, path: item.path, url: item.url });
      setFileContent(item.path, fileData.content, fileData.sha);
    } catch (e) {
      if (e.message?.includes('too large')) {
        Alert.alert('File too large', 'This file is too large to display in the editor.');
      } else {
        Alert.alert('Error opening file', e.message);
      }
    }
  };

  const selectRepo = (repo) => {
    useStore.getState().setActiveRepo(repo);
    useStore.getState().setActiveBranch(repo.default_branch || 'main');
  };

  const filteredRepos = repos.filter(r =>
    r.name.toLowerCase().includes(repoSearch.toLowerCase())
  );

  if (showRepos || !activeRepo) {
    return (
      <View style={styles.container}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>REPOSITORIES</Text>
          <TouchableOpacity onPress={loadRepos}>
            <MaterialCommunityIcons name="refresh" size={16} color={Colors.text_secondary} />
          </TouchableOpacity>
        </View>

        <View style={styles.searchBox}>
          <MaterialCommunityIcons name="magnify" size={14} color={Colors.text_secondary} />
          <TextInput
            style={styles.searchInput}
            value={repoSearch}
            onChangeText={setRepoSearch}
            placeholder="Search repos..."
            placeholderTextColor={Colors.text_secondary}
            autoCapitalize="none"
          />
        </View>

        {loading ? (
          <ActivityIndicator style={{ marginTop: 20 }} color={Colors.accent} />
        ) : (
          <ScrollView>
            {filteredRepos.map((repo) => (
              <TouchableOpacity key={repo.id} style={styles.repoItem} onPress={() => selectRepo(repo)}>
                <MaterialCommunityIcons
                  name={repo.private ? 'lock' : 'github'}
                  size={14} color={Colors.text_secondary} style={{ marginRight: 6 }}
                />
                <View style={{ flex: 1 }}>
                  <Text style={styles.repoName} numberOfLines={1}>{repo.name}</Text>
                  <Text style={styles.repoDesc} numberOfLines={1}>
                    {repo.description || repo.language || ''}
                  </Text>
                </View>
                <MaterialCommunityIcons name="chevron-right" size={14} color={Colors.text_secondary} />
              </TouchableOpacity>
            ))}
          </ScrollView>
        )}
      </View>
    );
  }

  const [owner, repo] = activeRepo.full_name.split('/');

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => setShowRepos(true)} style={styles.backRepo}>
          <MaterialCommunityIcons name="chevron-left" size={14} color={Colors.text_secondary} />
          <Text style={styles.headerTitle} numberOfLines={1}>{activeRepo.name.toUpperCase()}</Text>
        </TouchableOpacity>
        <View style={styles.headerActions}>
          <TouchableOpacity onPress={loadRoot} style={styles.iconBtn}>
            <MaterialCommunityIcons name="refresh" size={15} color={Colors.text_secondary} />
          </TouchableOpacity>
          <TouchableOpacity onPress={() => navigation?.navigate?.('NewFile')} style={styles.iconBtn}>
            <MaterialCommunityIcons name="file-plus-outline" size={15} color={Colors.text_secondary} />
          </TouchableOpacity>
        </View>
      </View>

      {loading ? (
        <ActivityIndicator style={{ marginTop: 20 }} color={Colors.accent} />
      ) : (
        <ScrollView>
          {rootItems.map((item) => (
            <FileTreeItem
              key={item.path}
              item={item}
              depth={0}
              repoInfo={{ owner, repo }}
              onFileOpen={handleFileOpen}
            />
          ))}
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
  headerActions: { flexDirection: 'row' },
  iconBtn: { padding: 3 },
  backRepo: { flexDirection: 'row', alignItems: 'center', flex: 1 },

  searchBox: {
    flexDirection: 'row', alignItems: 'center',
    margin: 8, paddingHorizontal: 8,
    backgroundColor: Colors.bg_input, borderRadius: 4,
    height: 28,
  },
  searchInput: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.xs, marginLeft: 4 },

  repoItem: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 12, paddingVertical: 8,
    borderBottomWidth: 1, borderColor: Colors.border + '40',
  },
  repoName: { color: Colors.text_primary, fontSize: FontSizes.sm },
  repoDesc: { color: Colors.text_secondary, fontSize: FontSizes.xs, marginTop: 1 },

  treeItem: {
    flexDirection: 'row', alignItems: 'center',
    height: 24, paddingRight: 8,
  },
  itemName: { color: Colors.text_primary, fontSize: 13, flex: 1 },
});
