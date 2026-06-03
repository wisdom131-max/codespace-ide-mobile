import React from 'react';
import { View, Text, TouchableOpacity, ScrollView, StyleSheet } from 'react-native';
import { Colors } from '../theme/colors';

const SYMBOLS = ['{','}','[',']','(',')',';',':','=','+','-','*','/','<','>','"',"'",'`','|','&','!','?','@','#','$','%','^','~','\\'];
const ACTIONS = ['Tab','Esc','Undo','Redo'];

export default function CodingToolbar({ onInsert, onAction }) {
  return (
    <View style={styles.container}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
        {ACTIONS.map((a) => (
          <TouchableOpacity key={a} style={styles.actionBtn} onPress={() => onAction?.(a)}>
            <Text style={styles.actionText}>{a}</Text>
          </TouchableOpacity>
        ))}
        <View style={styles.divider} />
        {SYMBOLS.map((s) => (
          <TouchableOpacity key={s} style={styles.symbolBtn} onPress={() => onInsert?.(s)}>
            <Text style={styles.symbolText}>{s}</Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    height: 40,
    backgroundColor: '#2d2d2d',
    borderTopWidth: 1,
    borderColor: '#474747',
  },
  row: { alignItems: 'center', paddingHorizontal: 4 },
  symbolBtn: {
    width: 34, height: 34, alignItems: 'center', justifyContent: 'center',
    marginHorizontal: 1, borderRadius: 4, backgroundColor: '#3c3c3c',
  },
  symbolText: { color: '#cccccc', fontSize: 14, fontFamily: 'monospace' },
  actionBtn: {
    paddingHorizontal: 10, height: 34, alignItems: 'center', justifyContent: 'center',
    marginHorizontal: 2, borderRadius: 4, backgroundColor: '#007acc',
  },
  actionText: { color: '#fff', fontSize: 11, fontWeight: '600' },
  divider: { width: 1, height: 24, backgroundColor: '#474747', marginHorizontal: 4 },
});
