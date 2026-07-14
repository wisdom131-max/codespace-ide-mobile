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
 *
 * String rules used here:
 *   - Triple-quoted strings must NOT contain """ inside them.
 *   - Use ${'$'} to emit a literal dollar sign inside a triple-quoted string.
 *   - Python docstrings use single-quoted ''' which is safe in Kotlin.
 *   - JS/TS template literals: use ${'$'}{VAR} to emit the dollar sign.
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
                ProjectType.ANDROID      -> scaffoldAndroid(root, projectName)
                ProjectType.FLUTTER      -> scaffoldFlutter(root, projectName)
                ProjectType.REACT_NATIVE -> scaffoldReactNative(root, projectName)
                ProjectType.WEB          -> scaffoldWeb(root, projectName)
                ProjectType.NODEJS       -> scaffoldNodeJs(root, projectName)
                ProjectType.PYTHON       -> scaffoldPython(root, projectName)
                ProjectType.EMPTY        -> scaffoldEmpty(root)
            }
            ScaffoldResult(true, "Project created at ${root.absolutePath}", root)
        } catch (e: Exception) {
            ScaffoldResult(false, "Failed to create project: ${e.message}", root)
        }
    }

    // ── Android ──────────────────────────────────────────────────────────────

    private fun scaffoldAndroid(root: File, name: String) {
        val safeName = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        val pkg = "com.example.$safeName"
        val pkgPath = pkg.replace('.', '/')
        val appDir = File(root, "app/src/main/java/$pkgPath").apply { mkdirs() }
        File(root, "app/src/main/res/values").mkdirs()
        File(root, "app/src/main/res/layout").mkdirs()

        write(File(root, "settings.gradle.kts"),
            "rootProject.name = \"$name\"\n" +
            "include(\":app\")\n")

        write(File(root, "build.gradle.kts"),
            "plugins {\n" +
            "    id(\"com.android.application\") version \"8.2.0\" apply false\n" +
            "    id(\"org.jetbrains.kotlin.android\") version \"1.9.22\" apply false\n" +
            "}\n")

        write(File(root, "app/build.gradle.kts"),
            "plugins {\n" +
            "    id(\"com.android.application\")\n" +
            "    id(\"org.jetbrains.kotlin.android\")\n" +
            "}\n" +
            "android {\n" +
            "    namespace = \"$pkg\"\n" +
            "    compileSdk = 34\n" +
            "    defaultConfig {\n" +
            "        applicationId = \"$pkg\"\n" +
            "        minSdk = 26\n" +
            "        targetSdk = 34\n" +
            "        versionCode = 1\n" +
            "        versionName = \"1.0\"\n" +
            "    }\n" +
            "    buildFeatures { compose = true }\n" +
            "    composeOptions { kotlinCompilerExtensionVersion = \"1.5.8\" }\n" +
            "}\n" +
            "dependencies {\n" +
            "    implementation(\"androidx.core:core-ktx:1.12.0\")\n" +
            "    implementation(\"androidx.activity:activity-compose:1.8.2\")\n" +
            "    implementation(platform(\"androidx.compose:compose-bom:2024.02.00\"))\n" +
            "    implementation(\"androidx.compose.ui:ui\")\n" +
            "    implementation(\"androidx.compose.material3:material3\")\n" +
            "}\n")

        // MainActivity — use string concat to avoid $-interpolation issues
        val dollarSign = "\$"
        write(File(appDir, "MainActivity.kt"),
            "package $pkg\n\n" +
            "import android.os.Bundle\n" +
            "import androidx.activity.ComponentActivity\n" +
            "import androidx.activity.compose.setContent\n" +
            "import androidx.compose.material3.MaterialTheme\n" +
            "import androidx.compose.material3.Text\n" +
            "import androidx.compose.runtime.Composable\n\n" +
            "class MainActivity : ComponentActivity() {\n" +
            "    override fun onCreate(savedInstanceState: Bundle?) {\n" +
            "        super.onCreate(savedInstanceState)\n" +
            "        setContent {\n" +
            "            MaterialTheme {\n" +
            "                Greeting(\"World\")\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "}\n\n" +
            "@Composable\n" +
            "fun Greeting(name: String) {\n" +
            "    Text(text = \"Hello, ${dollarSign}name!\")\n" +
            "}\n")

        write(File(root, "app/src/main/AndroidManifest.xml"),
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
            "    <application\n" +
            "        android:label=\"$name\"\n" +
            "        android:theme=\"@style/Theme.AppCompat\">\n" +
            "        <activity\n" +
            "            android:name=\".MainActivity\"\n" +
            "            android:exported=\"true\">\n" +
            "            <intent-filter>\n" +
            "                <action android:name=\"android.intent.action.MAIN\"/>\n" +
            "                <category android:name=\"android.intent.category.LAUNCHER\"/>\n" +
            "            </intent-filter>\n" +
            "        </activity>\n" +
            "    </application>\n" +
            "</manifest>\n")

        write(File(root, ".gitignore"), "*.iml\n.gradle/\nbuild/\n*.keystore\nlocal.properties\n")
        write(File(root, "README.md"), "# $name\n\nAndroid app generated by Codespace IDE.\n")
    }

    // ── Flutter ───────────────────────────────────────────────────────────────

    private fun scaffoldFlutter(root: File, name: String) {
        val pkg = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        File(root, "lib").mkdirs()
        File(root, "test").mkdirs()
        File(root, "android/app/src/main").mkdirs()

        write(File(root, "pubspec.yaml"),
            "name: $pkg\n" +
            "description: A Flutter application.\n" +
            "version: 1.0.0+1\n" +
            "environment:\n" +
            "  sdk: '>=3.0.0 <4.0.0'\n" +
            "  flutter: '>=3.10.0'\n" +
            "dependencies:\n" +
            "  flutter:\n" +
            "    sdk: flutter\n" +
            "dev_dependencies:\n" +
            "  flutter_test:\n" +
            "    sdk: flutter\n" +
            "flutter:\n" +
            "  uses-material-design: true\n")

        // Dart uses $ but we write via string concat so Kotlin won't interpolate
        val d = "\$"
        write(File(root, "lib/main.dart"),
            "import 'package:flutter/material.dart';\n\n" +
            "void main() {\n" +
            "  runApp(const MyApp());\n" +
            "}\n\n" +
            "class MyApp extends StatelessWidget {\n" +
            "  const MyApp({super.key});\n" +
            "  @override\n" +
            "  Widget build(BuildContext context) {\n" +
            "    return MaterialApp(\n" +
            "      title: '$name',\n" +
            "      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue)),\n" +
            "      home: const MyHomePage(title: '$name'),\n" +
            "    );\n" +
            "  }\n" +
            "}\n\n" +
            "class MyHomePage extends StatefulWidget {\n" +
            "  const MyHomePage({super.key, required this.title});\n" +
            "  final String title;\n" +
            "  @override\n" +
            "  State<MyHomePage> createState() => _MyHomePageState();\n" +
            "}\n\n" +
            "class _MyHomePageState extends State<MyHomePage> {\n" +
            "  int _counter = 0;\n" +
            "  void _increment() => setState(() => _counter++);\n" +
            "  @override\n" +
            "  Widget build(BuildContext context) {\n" +
            "    return Scaffold(\n" +
            "      appBar: AppBar(title: Text(widget.title)),\n" +
            "      body: Center(child: Text('Count: ${d}_counter', style: Theme.of(context).textTheme.headlineMedium)),\n" +
            "      floatingActionButton: FloatingActionButton(onPressed: _increment, child: const Icon(Icons.add)),\n" +
            "    );\n" +
            "  }\n" +
            "}\n")

        write(File(root, ".gitignore"), ".dart_tool/\n.flutter-plugins\nbuild/\npubspec.lock\n")
        write(File(root, "README.md"), "# $name\n\nFlutter app generated by Codespace IDE.\n")
    }

    // ── React Native ─────────────────────────────────────────────────────────

    private fun scaffoldReactNative(root: File, name: String) {
        val nameLower = name.lowercase()
        File(root, "src").mkdirs()

        write(File(root, "package.json"),
            "{\n" +
            "  \"name\": \"$nameLower\",\n" +
            "  \"version\": \"0.0.1\",\n" +
            "  \"private\": true,\n" +
            "  \"scripts\": {\n" +
            "    \"android\": \"react-native run-android\",\n" +
            "    \"ios\": \"react-native run-ios\",\n" +
            "    \"start\": \"react-native start\",\n" +
            "    \"test\": \"jest\"\n" +
            "  },\n" +
            "  \"dependencies\": {\n" +
            "    \"react\": \"18.2.0\",\n" +
            "    \"react-native\": \"0.73.0\"\n" +
            "  },\n" +
            "  \"devDependencies\": {\n" +
            "    \"@babel/core\": \"^7.20.0\",\n" +
            "    \"babel-jest\": \"^29.2.1\",\n" +
            "    \"jest\": \"^29.2.1\",\n" +
            "    \"react-test-renderer\": \"18.2.0\"\n" +
            "  }\n" +
            "}\n")

        write(File(root, "App.tsx"),
            "import React from 'react';\n" +
            "import {SafeAreaView, Text, StyleSheet} from 'react-native';\n\n" +
            "function App(): React.JSX.Element {\n" +
            "  return (\n" +
            "    <SafeAreaView style={styles.container}>\n" +
            "      <Text style={styles.text}>Hello, $name!</Text>\n" +
            "    </SafeAreaView>\n" +
            "  );\n" +
            "}\n\n" +
            "const styles = StyleSheet.create({\n" +
            "  container: {flex: 1, alignItems: 'center', justifyContent: 'center'},\n" +
            "  text: {fontSize: 24, fontWeight: 'bold'},\n" +
            "});\n\n" +
            "export default App;\n")

        write(File(root, ".gitignore"), "node_modules/\nbuild/\nandroid/app/build/\nios/Pods/\n")
        write(File(root, "README.md"), "# $name\n\nReact Native app generated by Codespace IDE.\n")
    }

    // ── Web ───────────────────────────────────────────────────────────────────

    private fun scaffoldWeb(root: File, name: String) {
        File(root, "css").mkdirs()
        File(root, "js").mkdirs()

        write(File(root, "index.html"),
            "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\"/>\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n" +
            "  <title>$name</title>\n" +
            "  <link rel=\"stylesheet\" href=\"css/style.css\"/>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <h1>Hello, $name!</h1>\n" +
            "  <script src=\"js/main.js\"></script>\n" +
            "</body>\n" +
            "</html>\n")

        write(File(root, "css/style.css"),
            "* { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "body { font-family: system-ui, sans-serif; padding: 2rem; }\n" +
            "h1 { color: #333; }\n")

        write(File(root, "js/main.js"), "// $name\nconsole.log('Hello from $name');\n")
        write(File(root, ".gitignore"), "node_modules/\ndist/\n.DS_Store\n")
        write(File(root, "README.md"), "# $name\n\nWeb app generated by Codespace IDE.\n")
    }

    // ── Node.js ───────────────────────────────────────────────────────────────

    private fun scaffoldNodeJs(root: File, name: String) {
        val nameLower = name.lowercase()
        val d = "\$"
        File(root, "src").mkdirs()

        write(File(root, "package.json"),
            "{\n" +
            "  \"name\": \"$nameLower\",\n" +
            "  \"version\": \"1.0.0\",\n" +
            "  \"description\": \"\",\n" +
            "  \"main\": \"src/index.js\",\n" +
            "  \"scripts\": {\n" +
            "    \"start\": \"node src/index.js\",\n" +
            "    \"dev\": \"nodemon src/index.js\",\n" +
            "    \"test\": \"jest\"\n" +
            "  },\n" +
            "  \"dependencies\": {},\n" +
            "  \"devDependencies\": {\n" +
            "    \"nodemon\": \"^3.0.0\"\n" +
            "  }\n" +
            "}\n")

        // JS template literals use ${PORT} — write via concat with d="\$" to avoid Kotlin interpolation
        write(File(root, "src/index.js"),
            "'use strict';\n\n" +
            "const http = require('http');\n" +
            "const PORT = process.env.PORT || 3000;\n\n" +
            "const server = http.createServer((req, res) => {\n" +
            "  res.writeHead(200, {'Content-Type': 'text/plain'});\n" +
            "  res.end('Hello from $name!\\n');\n" +
            "});\n\n" +
            "server.listen(PORT, () => {\n" +
            "  console.log(`$name running on http://localhost:${d}{PORT}`);\n" +
            "});\n")

        write(File(root, ".gitignore"), "node_modules/\n.env\n*.log\n")
        write(File(root, "README.md"), "# $name\n\nNode.js project generated by Codespace IDE.\n")
    }

    // ── Python ────────────────────────────────────────────────────────────────

    private fun scaffoldPython(root: File, name: String) {
        File(root, "src").mkdirs()
        File(root, "tests").mkdirs()

        // Python docstrings: use single-quoted ''' to avoid breaking Kotlin triple-quote strings
        write(File(root, "src/main.py"),
            "#!/usr/bin/env python3\n" +
            "'''\n" +
            "$name\n" +
            "Generated by Codespace IDE.\n" +
            "'''\n\n\n" +
            "def main():\n" +
            "    print('Hello from $name!')\n\n\n" +
            "if __name__ == '__main__':\n" +
            "    main()\n")

        write(File(root, "tests/test_main.py"),
            "import sys\n" +
            "import os\n" +
            "sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))\n\n\n" +
            "def test_placeholder():\n" +
            "    assert True\n")

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
