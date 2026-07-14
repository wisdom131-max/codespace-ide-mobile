package com.codespace.ide.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 12-B — Project Templates
 *
 * Scaffolds starter files for each project type.
 * Called by ProjectWizard after the user confirms a new project.
 */
object ProjectTemplates {

    enum class ProjectType(val displayName: String, val description: String) {
        ANDROID("Android App", "Kotlin + Jetpack Compose"),
        FLUTTER("Flutter App", "Dart + Flutter SDK"),
        REACT_NATIVE("React Native", "JavaScript / TypeScript"),
        WEB("Web App", "HTML + CSS + JS"),
        NODEJS("Node.js Project", "JavaScript / TypeScript backend"),
        PYTHON("Python Project", "Python 3 script or package"),
        EMPTY("Empty Project", "Blank directory — start from scratch"),
    }

    data class ScaffoldResult(val success: Boolean, val message: String, val rootDir: File)

    /**
     * Create project scaffold on disk.
     * @param context Android context
     * @param projectName Sanitized project name
     * @param type Project type
     * @param rootParent Parent directory for the new project folder
     */
    suspend fun scaffold(
        context: Context,
        projectName: String,
        type: ProjectType,
        rootParent: File,
    ): ScaffoldResult = withContext(Dispatchers.IO) {
        val root = File(rootParent, projectName)
        if (root.exists()) {
            return@withContext ScaffoldResult(false, "Directory already exists: ${root.absolutePath}", root)
        }
        try {
            root.mkdirs()
            when (type) {
                ProjectType.ANDROID     -> scaffoldAndroid(root, projectName)
                ProjectType.FLUTTER     -> scaffoldFlutter(root, projectName)
                ProjectType.REACT_NATIVE -> scaffoldReactNative(root, projectName)
                ProjectType.WEB         -> scaffoldWeb(root, projectName)
                ProjectType.NODEJS      -> scaffoldNodeJs(root, projectName)
                ProjectType.PYTHON      -> scaffoldPython(root, projectName)
                ProjectType.EMPTY       -> scaffoldEmpty(root)
            }
            ScaffoldResult(true, "Project created at ${root.absolutePath}", root)
        } catch (e: Exception) {
            ScaffoldResult(false, "Failed to create project: ${e.message}", root)
        }
    }

    // ── Android ──────────────────────────────────────────────────────────────

    private fun scaffoldAndroid(root: File, name: String) {
        val pkg = "com.example.${name.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        val appDir = File(root, "app/src/main/java/${pkg.replace('.', '/')}").apply { mkdirs() }
        File(root, "app/src/main/res/values").mkdirs()
        File(root, "app/src/main/res/layout").mkdirs()

        write(File(root, "settings.gradle.kts"), """
            rootProject.name = "$name"
            include(":app")
        """.trimIndent())

        write(File(root, "build.gradle.kts"), """
            plugins {
                id("com.android.application") version "8.2.0" apply false
                id("org.jetbrains.kotlin.android") version "1.9.22" apply false
            }
        """.trimIndent())

        write(File(root, "app/build.gradle.kts"), """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "$pkg"
                compileSdk = 34
                defaultConfig {
                    applicationId = "$pkg"
                    minSdk = 26
                    targetSdk = 34
                    versionCode = 1
                    versionName = "1.0"
                }
                buildFeatures { compose = true }
                composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.12.0")
                implementation("androidx.activity:activity-compose:1.8.2")
                implementation(platform("androidx.compose:compose-bom:2024.02.00"))
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.material3:material3")
            }
        """.trimIndent())

        write(File(appDir, "MainActivity.kt"), """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Greeting("World")
                        }
                    }
                }
            }

            @Composable
            fun Greeting(name: String) {
                Text(text = "Hello, ${'$'}name!")
            }
        """.trimIndent())

        write(File(root, "app/src/main/AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="$name"
                    android:theme="@style/Theme.AppCompat">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        write(File(root, ".gitignore"), "*.iml\n.gradle/\nbuild/\n*.keystore\nlocal.properties\n")
        write(File(root, "README.md"), "# $name\n\nAndroid app generated by Codespace IDE.\n")
    }

    // ── Flutter ───────────────────────────────────────────────────────────────

    private fun scaffoldFlutter(root: File, name: String) {
        val pkg = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        File(root, "lib").mkdirs()
        File(root, "test").mkdirs()
        File(root, "android/app/src/main").mkdirs()
        File(root, "ios/Runner").mkdirs()

        write(File(root, "pubspec.yaml"), """
            name: $pkg
            description: A Flutter application.
            version: 1.0.0+1
            environment:
              sdk: '>=3.0.0 <4.0.0'
              flutter: '>=3.10.0'
            dependencies:
              flutter:
                sdk: flutter
            dev_dependencies:
              flutter_test:
                sdk: flutter
            flutter:
              uses-material-design: true
        """.trimIndent())

        write(File(root, "lib/main.dart"), """
            import 'package:flutter/material.dart';

            void main() {
              runApp(const MyApp());
            }

            class MyApp extends StatelessWidget {
              const MyApp({super.key});
              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: '${name}',
                  theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue)),
                  home: const MyHomePage(title: '${name}'),
                );
              }
            }

            class MyHomePage extends StatefulWidget {
              const MyHomePage({super.key, required this.title});
              final String title;
              @override
              State<MyHomePage> createState() => _MyHomePageState();
            }

            class _MyHomePageState extends State<MyHomePage> {
              int _counter = 0;
              void _increment() => setState(() => _counter++);
              @override
              Widget build(BuildContext context) {
                return Scaffold(
                  appBar: AppBar(title: Text(widget.title)),
                  body: Center(child: Text('Count: $_counter', style: Theme.of(context).textTheme.headlineMedium)),
                  floatingActionButton: FloatingActionButton(onPressed: _increment, child: const Icon(Icons.add)),
                );
              }
            }
        """.trimIndent())

        write(File(root, ".gitignore"), ".dart_tool/\n.flutter-plugins\nbuild/\npubspec.lock\n")
        write(File(root, "README.md"), "# $name\n\nFlutter app generated by Codespace IDE.\n")
    }

    // ── React Native ─────────────────────────────────────────────────────────

    private fun scaffoldReactNative(root: File, name: String) {
        File(root, "src").mkdirs()

        write(File(root, "package.json"), """
            {
              "name": "${name.lowercase()}",
              "version": "0.0.1",
              "private": true,
              "scripts": {
                "android": "react-native run-android",
                "ios": "react-native run-ios",
                "start": "react-native start",
                "test": "jest"
              },
              "dependencies": {
                "react": "18.2.0",
                "react-native": "0.73.0"
              },
              "devDependencies": {
                "@babel/core": "^7.20.0",
                "@babel/preset-env": "^7.20.0",
                "babel-jest": "^29.2.1",
                "jest": "^29.2.1",
                "react-test-renderer": "18.2.0"
              }
            }
        """.trimIndent())

        write(File(root, "App.tsx"), """
            import React from 'react';
            import {SafeAreaView, Text, StyleSheet} from 'react-native';

            function App(): React.JSX.Element {
              return (
                <SafeAreaView style={styles.container}>
                  <Text style={styles.text}>Hello, $name!</Text>
                </SafeAreaView>
              );
            }

            const styles = StyleSheet.create({
              container: {flex: 1, alignItems: 'center', justifyContent: 'center'},
              text: {fontSize: 24, fontWeight: 'bold'},
            });

            export default App;
        """.trimIndent())

        write(File(root, ".gitignore"), "node_modules/\nbuild/\nandroid/app/build/\nios/Pods/\n")
        write(File(root, "README.md"), "# $name\n\nReact Native app generated by Codespace IDE.\n")
    }

    // ── Web ───────────────────────────────────────────────────────────────────

    private fun scaffoldWeb(root: File, name: String) {
        File(root, "css").mkdirs()
        File(root, "js").mkdirs()

        write(File(root, "index.html"), """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
              <title>$name</title>
              <link rel="stylesheet" href="css/style.css"/>
            </head>
            <body>
              <h1>Hello, $name!</h1>
              <script src="js/main.js"></script>
            </body>
            </html>
        """.trimIndent())

        write(File(root, "css/style.css"), """
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body { font-family: system-ui, sans-serif; padding: 2rem; }
            h1 { color: #333; }
        """.trimIndent())

        write(File(root, "js/main.js"), "// $name\nconsole.log('Hello from $name');\n")
        write(File(root, ".gitignore"), "node_modules/\ndist/\n.DS_Store\n")
        write(File(root, "README.md"), "# $name\n\nWeb app generated by Codespace IDE.\n")
    }

    // ── Node.js ───────────────────────────────────────────────────────────────

    private fun scaffoldNodeJs(root: File, name: String) {
        File(root, "src").mkdirs()

        write(File(root, "package.json"), """
            {
              "name": "${name.lowercase()}",
              "version": "1.0.0",
              "description": "",
              "main": "src/index.js",
              "scripts": {
                "start": "node src/index.js",
                "dev": "nodemon src/index.js",
                "test": "jest"
              },
              "dependencies": {},
              "devDependencies": {
                "nodemon": "^3.0.0"
              }
            }
        """.trimIndent())

        write(File(root, "src/index.js"), """
            'use strict';

            const http = require('http');

            const PORT = process.env.PORT || 3000;

            const server = http.createServer((req, res) => {
              res.writeHead(200, {'Content-Type': 'text/plain'});
              res.end('Hello from $name!\n');
            });

            server.listen(PORT, () => {
              console.log(`$name running on http://localhost:${'$'}{PORT}`);
            });
        """.trimIndent())

        write(File(root, ".gitignore"), "node_modules/\n.env\n*.log\n")
        write(File(root, "README.md"), "# $name\n\nNode.js project generated by Codespace IDE.\n")
    }

    // ── Python ────────────────────────────────────────────────────────────────

    private fun scaffoldPython(root: File, name: String) {
        File(root, "src").mkdirs()
        File(root, "tests").mkdirs()

        write(File(root, "src/main.py"), """
            #!/usr/bin/env python3
            """
            $name
            Generated by Codespace IDE.
            """


            def main():
                print("Hello from $name!")


            if __name__ == "__main__":
                main()
        """.trimIndent())

        write(File(root, "tests/test_main.py"), """
            import sys
            import os
            sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))


            def test_placeholder():
                assert True
        """.trimIndent())

        write(File(root, "requirements.txt"), "# Add your dependencies here\n")
        write(File(root, ".gitignore"), "__pycache__/\n*.pyc\n.venv/\ndist/\nbuild/\n*.egg-info/\n")
        write(File(root, "README.md"), "# $name\n\nPython project generated by Codespace IDE.\n")
    }

    // ── Empty ─────────────────────────────────────────────────────────────────

    private fun scaffoldEmpty(root: File) {
        write(File(root, "README.md"), "# ${root.name}\n\nEmpty project created by Codespace IDE.\n")
        write(File(root, ".gitignore"), ".DS_Store\n*.log\n")
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
