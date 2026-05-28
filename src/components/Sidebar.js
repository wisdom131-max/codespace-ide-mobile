import React, { useState, useEffect } from 'react';
import {
  View, Text, TouchableOpacity, ScrollView,
  StyleSheet, TextInput, ActivityIndicator, RefreshControl,
} from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes, Spacing } from '../theme/colors';
import useStore from '../hooks/useStore';
import githubService from '../services/github';
import ExplorerPanel from './panels/ExplorerPanel';
import SearchPanel from './panels/SearchPanel';
import GitPanel from './panels/GitPanel';
import CodespacePanel from './panels/CodespacePanel';
import ExtensionsPanel from './panels/ExtensionsPanel';

export default function Sidebar({ navigation }) {
  const { sidebarTab } = useStore();

  const panels = {
    explorer:   <ExplorerPanel navigation={navigation} />,
    search:     <SearchPanel />,
    git:        <GitPanel />,
    codespace:  <CodespacePanel />,
    extensions: <ExtensionsPanel />,
  };

  return (
    <View style={styles.sidebar}>
      {panels[sidebarTab] || null}
    </View>
  );
}

const styles = StyleSheet.create({
  sidebar: {
    width: 240,
    backgroundColor: Colors.bg_sidebar,
    borderRightWidth: 1,
    borderColor: Colors.border,
  },
});
