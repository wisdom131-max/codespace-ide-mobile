import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors, FontSizes } from '../theme/colors';
import useStore from '../hooks/useStore';

export default function VSStatusBar({ branch, language, line, col, isDirty, repoName }) {
  const { togglePanel, panelTab, setPanelTab, activeCodespace } = useStore();

  return (
    <View style={styles.bar}>
      {/* Left */}
      <View style={styles.left}>
        {activeCodespace ? (
          <TouchableOpacity style={styles.item}>
            <MaterialCommunityIcons name="remote" size={12} color="#fff" />
            <Text style={styles.text}> {activeCodespace.name}</Text>
          </TouchableOpacity>
        ) : (
          <TouchableOpacity style={styles.item}>
            <MaterialCommunityIcons name="source-branch" size={12} color="#fff" />
            <Text style={styles.text}> {branch || 'main'}</Text>
          </TouchableOpacity>
        )}

        {repoName && (
          <TouchableOpacity style={styles.item}>
            <MaterialCommunityIcons name="github" size={12} color="#fff" />
            <Text style={styles.text}> {repoName}</Text>
          </TouchableOpacity>
        )}

        {isDirty && (
          <View style={styles.item}>
            <Text style={[styles.text, { color: Colors.warning }]}>● Unsaved</Text>
          </View>
        )}
      </View>

      {/* Right */}
      <View style={styles.right}>
        <TouchableOpacity
          style={styles.item}
          onPress={() => { setPanelTab('problems'); }}
        >
          <MaterialCommunityIcons name="alert-circle-outline" size={12} color="#fff" />
          <Text style={styles.text}> 0</Text>
          <MaterialCommunityIcons name="alert-outline" size={12} color="#fff" style={{ marginLeft: 6 }} />
          <Text style={styles.text}> 0</Text>
        </TouchableOpacity>

        <View style={styles.item}>
          <Text style={styles.text}>Ln {line}, Col {col}</Text>
        </View>

        <View style={styles.item}>
          <Text style={styles.text}>{language}</Text>
        </View>

        <View style={styles.item}>
          <Text style={styles.text}>UTF-8</Text>
        </View>

        <TouchableOpacity style={styles.item} onPress={togglePanel}>
          <MaterialCommunityIcons name="console" size={12} color="#fff" />
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    height: 22,
    backgroundColor: Colors.bg_statusbar,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 8,
  },
  left:  { flexDirection: 'row', alignItems: 'center' },
  right: { flexDirection: 'row', alignItems: 'center' },
  item: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 6, height: 22,
  },
  text: { color: '#fff', fontSize: 11 },
});
