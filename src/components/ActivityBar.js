import React from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Colors } from '../theme/colors';
import useStore from '../hooks/useStore';

const ITEMS = [
  { id: 'explorer',   icon: 'file-multiple-outline',  label: 'Explorer' },
  { id: 'search',     icon: 'magnify',                 label: 'Search' },
  { id: 'git',        icon: 'source-branch',           label: 'Source Control' },
  { id: 'codespace',  icon: 'cloud-outline',           label: 'Codespaces' },
  { id: 'extensions', icon: 'puzzle-outline',          label: 'Extensions' },
];

const BOTTOM = [
  { id: 'palette',  icon: 'chevron-right',           label: 'Command Palette' },
  { id: 'settings', icon: 'cog-outline',             label: 'Settings' },
  { id: 'account',  icon: 'account-circle-outline',  label: 'Account' },
];

export default function ActivityBar({ navigation, onPalette }) {
  const { sidebarTab, setSidebarTab, toggleSidebar, sidebarVisible, gitChanges } = useStore();

  const handlePress = (id) => {
    if (id === 'settings') { navigation.navigate('Settings'); return; }
    if (id === 'account')  { navigation.navigate('Profile');  return; }
    if (id === 'palette')  { onPalette?.(); return; }
    // Toggle sidebar if tapping the already-active tab
    if (sidebarTab === id && sidebarVisible) {
      toggleSidebar();
    } else {
      setSidebarTab(id);
    }
  };

  return (
    <View style={styles.bar}>
      <View style={styles.top}>
        {ITEMS.map((item) => (
          <TouchableOpacity
            key={item.id}
            style={[styles.item, sidebarTab === item.id && sidebarVisible && styles.itemActive]}
            onPress={() => handlePress(item.id)}
          >
            <MaterialCommunityIcons
              name={item.icon}
              size={24}
              color={sidebarTab === item.id && sidebarVisible ? Colors.text_active : Colors.text_secondary}
            />
            {item.id === 'git' && gitChanges.length > 0 && (
              <View style={styles.badge}>
                <View style={styles.badgeDot} />
              </View>
            )}
          </TouchableOpacity>
        ))}
      </View>
      <View style={styles.bottom}>
        {BOTTOM.map((item) => (
          <TouchableOpacity key={item.id} style={styles.item} onPress={() => handlePress(item.id)}>
            <MaterialCommunityIcons name={item.icon} size={24} color={Colors.text_secondary} />
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    width: 48,
    backgroundColor: Colors.bg_activitybar,
    flexDirection: 'column',
    justifyContent: 'space-between',
    paddingVertical: 8,
  },
  top:    { flexDirection: 'column' },
  bottom: { flexDirection: 'column' },
  item: {
    width: 48, height: 48,
    alignItems: 'center', justifyContent: 'center',
  },
  itemActive: {
    borderLeftWidth: 2,
    borderLeftColor: Colors.text_active,
  },
  badge: { position: 'absolute', top: 8, right: 8 },
  badgeDot: {
    width: 8, height: 8, borderRadius: 4,
    backgroundColor: Colors.accent,
  },
});
