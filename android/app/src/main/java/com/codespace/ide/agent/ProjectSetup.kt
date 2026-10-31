package com.codespace.ide.agent

/**
 * get_project_setup_info. Works out what this project IS from the files that are actually
 * there, then says how to build, test, and run it.
 *
 * Deliberately evidence-only: every line it reports names the file that proves it. A "setup
 * info" tool that guesses a framework from a folder name is worse than no tool, because the
 * model repeats the guess to the user as fact.
 */
internal object ProjectSetup {

    private data class Marker(
        val file: String,
        val kind: String,
        val build: String,
        val test: String,
        val run: String,
    )

    private val MARKERS = listOf(
        Marker("gradlew", "Android / Gradle", "./gradlew assembleDebug", "./gradlew test", "./gradlew installDebug"),
        Marker("build.gradle.kts", "Gradle (Kotlin DSL)", "./gradlew build", "./gradlew test", "./gradlew run"),
        Marker("build.gradle", "Gradle (Groovy)", "./gradlew build", "./gradlew test", "./gradlew run"),
        Marker("settings.gradle.kts", "Gradle multi-project", "./gradlew build", "./gradlew test", "./gradlew run"),
        Marker("package.json", "Node / npm", "npm install", "npm test", "npm start"),
        Marker("pnpm-lock.yaml", "Node / pnpm", "pnpm install", "pnpm test", "pnpm start"),
        Marker("yarn.lock", "Node / yarn", "yarn", "yarn test", "yarn start"),
        Marker("pom.xml", "Java / Maven", "mvn package", "mvn test", "mvn exec:java"),
        Marker("pyproject.toml", "Python (pyproject)", "pip install -e .", "pytest", "python -m <package>"),
        Marker("requirements.txt", "Python (requirements)", "pip install -r requirements.txt", "pytest", "python main.py"),
        Marker("go.mod", "Go", "go build ./...", "go test ./...", "go run ."),
        Marker("Cargo.toml", "Rust", "cargo build", "cargo test", "cargo run"),
        Marker("CMakeLists.txt", "C / C++ (CMake)", "cmake -B build && cmake --build build", "ctest --test-dir build", "./build/<target>"),
        Marker("Makefile", "Make", "make", "make test", "make run"),
        Marker("Dockerfile", "Container", "docker build -t app .", "—", "docker run app"),
    )

    /**
     * @param names entries in the project root (files and directories), not a full tree.
     */
    fun describe(names: Collection<String>): String {
        val present = names.map { it.trimEnd('/') }.toSet()
        val matched = MARKERS.filter { it.file in present }
        if (matched.isEmpty()) {
            return "No build marker found in the project root (" +
                present.sorted().take(12).joinToString(", ") + "). This project has no recognised " +
                "build system, so there is no reliable build or test command to report."
        }
        val seen = LinkedHashSet<String>()
        val sb = StringBuilder("Project setup, from build markers in the root:\n")
        for (m in matched) {
            if (!seen.add(m.kind)) continue
            sb.append("- ").append(m.kind).append(" (").append(m.file).append(")\n")
            sb.append("    build: ").append(m.build).append('\n')
            sb.append("    test:  ").append(m.test).append('\n')
            sb.append("    run:   ").append(m.run).append('\n')
        }
        val extras = present.filter { it == ".github" || it == "gradle.properties" || it == "local.properties" }
        if (extras.isNotEmpty()) sb.append("Also present: ").append(extras.sorted().joinToString(", ")).append('\n')
        return sb.toString().trim()
    }
}
