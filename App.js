import 'react-native-gesture-handler';
import React, { useEffect, useState } from 'react';
import { View, ActivityIndicator, StatusBar } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createStackNavigator } from '@react-navigation/stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import Toast from 'react-native-toast-message';

import LoginScreen from './src/screens/LoginScreen';
import EditorScreen from './src/screens/EditorScreen';
import SettingsScreen from './src/screens/SettingsScreen';
import ProfileScreen from './src/screens/ProfileScreen';

import AuthService from './src/services/auth';
import useStore from './src/hooks/useStore';
import { Colors } from './src/theme/colors';

const Stack = createStackNavigator();

export default function App() {
  const [initializing, setInitializing] = useState(true);
  const [initialRoute, setInitialRoute] = useState('Login');
  const { setUser } = useStore();

  useEffect(() => {
    checkAuth();
  }, []);

  const checkAuth = async () => {
    try {
      const isAuth = await AuthService.getSavedUser();
      if (isAuth) {
        setUser(isAuth);
        setInitialRoute('Main');
      }
    } catch (e) {}
    finally { setInitializing(false); }
  };

  if (initializing) {
    return (
      <View style={{ flex: 1, backgroundColor: Colors.bg_editor, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator size="large" color={Colors.accent} />
      </View>
    );
  }

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <StatusBar barStyle="light-content" backgroundColor={Colors.bg_activitybar} />
        <NavigationContainer
          theme={{
            dark: true,
            colors: {
              primary: Colors.accent,
              background: Colors.bg_editor,
              card: Colors.bg_titlebar,
              text: Colors.text_primary,
              border: Colors.border,
              notification: Colors.accent,
            },
          }}
        >
          <Stack.Navigator
            initialRouteName={initialRoute}
            screenOptions={{ headerShown: false, animation: 'fade' }}
          >
            <Stack.Screen name="Login"    component={LoginScreen} />
            <Stack.Screen name="Main"     component={EditorScreen} />
            <Stack.Screen name="Settings" component={SettingsScreen} />
            <Stack.Screen name="Profile"  component={ProfileScreen} />
          </Stack.Navigator>
        </NavigationContainer>
        <Toast />
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
