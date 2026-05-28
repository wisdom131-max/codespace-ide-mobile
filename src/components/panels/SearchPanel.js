import React, { useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  ScrollView, StyleSheet, ActivityIndicator,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../../theme/colors';
import useStore from '../../hooks/useStore';
import githubService from '../../services/github';

export default function SearchPanel() {
  const { activeRepo, user, openFile, setFileContent } = useStore();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setLoading(true);
    setSearched(true);
    try {
      const [owner, repo] = activeRepo?.full_name?.split('/') || [user?.login, ''];
      const items = await githubService.searchCode(query, owner, repo || undefined);
      setResults(items);
    } catch (e) {
      setResults([]);
    } finally {
      setLoading(false);
    }
  };

  const openResult = async (item) => {
    if (!activeRepo) return;
    const [owner, repo] = activeRepo.full_name.split('/');
    try {
      const fileData = await githubService.getFileContent(owner, repo, item.path);
      openFile({ name: item.name, path: item.path });
      setFileContent(item.path, fileData.content, fileData.sha);
    } catch (e) {}
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>SEARCH</Text>
      </View>

      <View style={styles.searchArea}>
        <View style={styles.searchBox}>
          <TextInput
            style={styles.searchInput}
            value={query}
            onChangeText={setQuery}
            placeholder="Search in files..."
            placeholderTextColor={Colors.text_secondary}
            autoCapitalize="none"
            returnKeyType="search"
            onSubmitEditing={handleSearch}
          />
          {query.length > 0 && (
            <TouchableOpacity onPress={() => { setQuery(''); setResults([]); setSearched(false); }}>
              <MaterialCommunityIcons name="close" size={14} color={Colors.text_secondary} />
            </TouchableOpacity>
          )}
        </View>
        <TouchableOpacity style={styles.searchBtn} onPress={handleSearch}>
          <MaterialCommunityIcons name="magnify" size={16} color="#fff" />
        </TouchableOpacity>
      </View>

      {!activeRepo && (
        <View style={styles.hint}>
          <MaterialCommunityIcons name="information-outline" size={16} color={Colors.text_secondary} />
          <Text style={styles.hintText}>Open a repository to search its files</Text>
        </View>
      )}

      {loading && <ActivityIndicator style={{ marginTop: 20 }} color={Colors.accent} />}

      {searched && !loading && results.length === 0 && (
        <View style={styles.noResults}>
          <Text style={styles.noResultsText}>No results for "{query}"</Text>
        </View>
      )}

      <ScrollView>
        {results.map((item, i) => (
          <TouchableOpacity key={i} style={styles.resultItem} onPress={() => openResult(item)}>
            <MaterialCommunityIcons name="file-search-outline" size={14} color={Colors.accent} style={{ marginRight: 6 }} />
            <View style={{ flex: 1 }}>
              <Text style={styles.resultName} numberOfLines={1}>{item.name}</Text>
              <Text style={styles.resultPath} numberOfLines={1}>{item.path}</Text>
            </View>
          </TouchableOpacity>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    paddingHorizontal: 10, paddingVertical: 6,
    borderBottomWidth: 1, borderColor: Colors.border,
  },
  headerTitle: { color: Colors.text_secondary, fontSize: 11, fontWeight: '600', letterSpacing: 1 },
  searchArea: { flexDirection: 'row', padding: 8, gap: 4 },
  searchBox: {
    flex: 1, flexDirection: 'row', alignItems: 'center',
    backgroundColor: Colors.bg_input, borderRadius: 4,
    paddingHorizontal: 8, height: 30,
    borderWidth: 1, borderColor: Colors.border,
  },
  searchInput: { flex: 1, color: Colors.text_primary, fontSize: FontSizes.xs },
  searchBtn: {
    backgroundColor: Colors.accent, borderRadius: 4,
    width: 30, height: 30, alignItems: 'center', justifyContent: 'center',
  },
  hint: {
    flexDirection: 'row', alignItems: 'center',
    padding: 12, gap: 6,
  },
  hintText: { color: Colors.text_secondary, fontSize: FontSizes.xs, flex: 1 },
  noResults: { padding: 12 },
  noResultsText: { color: Colors.text_secondary, fontSize: FontSizes.sm },
  resultItem: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 12, paddingVertical: 8,
    borderBottomWidth: 1, borderColor: Colors.border + '30',
  },
  resultName: { color: Colors.text_primary, fontSize: FontSizes.sm },
  resultPath: { color: Colors.text_secondary, fontSize: FontSizes.xs, marginTop: 1 },
});
