# Development Guide

## Project Structure

This is a multi-module Gradle project with Kotlin DSL:

- **`analyzer-api`** - Core analyzer interfaces and types (1% of codebase, compiled with javac)
- **`app`** - Java 21 main application with TreeSitter analyzers (94% of codebase, compiled with javac)

### Module Dependency Diagram

```
┌───────────────────────────────────────────────────────────┐
│                    Module Dependencies                    │
└───────────────────────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │        app           │
                    │                      │
                    │ • GUI Application    │
                    │ Compiler: javac      │
                    └──────────────────────┘
                             │
                             │ implementation
                             ▼
                    ┌─────────────────┐
                    │  analyzer-api   │
                    │                 │
                    │ Compiler: javac │
                    └─────────────────┘

Dependencies:
• app → analyzer-api
• analyzer-api → (no dependencies)
```

## Essential Gradle Tasks for New Developers

### Quick Start
- `./gradlew run` - Run the application (from app)
- `./gradlew build` - Full build (compile, test, check) - all modules + frontend
- `./gradlew assemble` - Build without tests - all modules + frontend

### Development Workflow - All Projects
- `./gradlew clean` - Clean build artifacts for all modules + frontend
- `./gradlew classes` - Compile all main sources (fastest for development)
- `./gradlew shadowJar` - Create fat JAR for distribution (explicit only)
- `./gradlew generateThemeCss` - Generate theme CSS variables from ThemeColors.java

### Frontend Build Tasks

#### Gradle Tasks (Recommended)
- `./gradlew frontendBuild` - Build frontend with Vite (includes npm install)
- `./gradlew frontendClean` - Clean frontend build artifacts and node_modules

#### Direct npm Commands (Development)
For frontend-only development, you can work directly in the `frontend-mop/` directory:

```bash
cd frontend-mop/

# Install dependencies
npm install

# Development server with hot reload
npm run dev

# Production build (outputs to app/src/main/resources/mop-web/)
npm run build

# Preview production build
npm run preview
```

**Note**: The npm `build` script runs both worker and main builds via Vite. Gradle automatically handles npm commands during the main build process.

### Development Workflow - Individual Projects
- `./gradlew :analyzer-api:compileJava` - Compile API interfaces only
- `./gradlew :app:compileJava` - Compile Java code only
- `./gradlew :analyzer-api:classes` - Compile analyzer-api (Java)
- `./gradlew :app:classes` - Compile app (Java)
- `./gradlew :analyzer-api:assemble` - Build API module only
- `./gradlew :app:assemble` - Build app project only

### Testing
- `./gradlew test` - Run all tests (includes TreeSitter and regular tests)
- `./gradlew check` - Run all checks (tests + static analysis)

#### Running Tests by Project
- `./gradlew :analyzer-api:test` - Run tests only in the API module
- `./gradlew :app:test` - Run tests only in the app Java project

#### Running Test Subsets
- `./gradlew test --tests "*AnalyzerTest"` - Run all analyzer tests (includes TreeSitter)
- `./gradlew test --tests "*.EditBlockTest"` - Run specific test class
- `./gradlew test --tests "*EditBlock*"` - Run tests matching pattern
- `./gradlew test --tests "io.github.jbellis.brokk.git.*"` - Run tests in package
- `./gradlew test --tests "*TypescriptAnalyzerTest"` - Run TreeSitter analyzer tests

#### Project-Specific Test Patterns
- `./gradlew :analyzer-api:test --tests "*Analyzer*"` - Run API interface tests
- `./gradlew :app:test --tests "*GitRepo*"` - Run Git-related tests in app project

#### Test Configuration Notes
- **Unified test suite**: All tests run together in a single forked JVM
- **Single fork strategy**: One JVM fork for the entire test run
- **Native library isolation**: TreeSitter tests safely isolated while maintaining good performance

#### Test Reports
After running tests, detailed reports are automatically generated:
- **All Projects**: `build/reports/tests/test/index.html` - Combined test results
- **analyzer-api**: `analyzer-api/build/reports/tests/test/index.html` - API module tests
- **app**: `app/build/reports/tests/test/index.html` - Java project tests
- **Console Output**: Real-time test progress with pass/fail/skip status

### Code Formatting

#### Java Code (app, analyzer-api)
- `./gradlew tidy` - Format all Java code. (Alias for `./gradlew spotlessApply`)
- `./gradlew spotlessCheck` - Check if code formatting is correct (CI-friendly)
- `./gradlew spotlessInstallGitPrePushHook` - Install pre-push hook for automatic formatting checks

### Dependency Management

The project uses the Gradle Dependency Analysis plugin to maintain clean dependencies:

- `./gradlew buildHealth` - Run comprehensive dependency analysis
- `./gradlew dependencies` - Show dependency tree with conflicts

#### What the dependency analysis checks:
- **Unused Dependencies**: Identifies dependencies that aren't actually used
- **Dependency Conflicts**: Detects version conflicts between transitive dependencies
- **Incorrect Configuration**: Finds dependencies in wrong configurations (e.g., runtime vs compile)
The dependency analysis runs automatically in CI and will fail builds on critical dependency issues.

### Static Analysis

#### ErrorProne (Java modules: analyzer-api, app)
- **Configuration**: Custom checks enabled in `build.gradle.kts` for enhanced error detection
- **Scope**: Applied to analyzer-api and app modules (Java code only)

#### NullAway (app module)
- **Null safety analysis**: Static analysis to prevent NullPointerExceptions
- **Configuration**: Configured with project-specific null safety rules
- **Scope**: Applied to app module only where most business logic resides

The build system uses aggressive multi-level caching for optimal performance:

### Cache Types
- **Local Build Cache**: Task outputs cached in `~/.gradle/caches/`
- **Configuration Cache**: Build configuration cached for faster startup
- **Incremental Compilation**: Only recompiles changed files
- **Daemon Caching**: JVM process reused across builds

### Cache Management
- `./gradlew clean` - Clear build outputs (keeps Gradle caches)
- `./gradlew --stop` - Stop Gradle daemon
- Manual cache clearing: `rm -rf ~/.gradle/caches/` (nuclear option)

### Performance Tips
- Keep Gradle daemon running (`gradle.properties` enables this with 6GB heap)
- Use `./gradlew :app:classes` for fastest Java-only development
- Use `./gradlew classes` to compile both projects
- Use `./gradlew assemble` for development (skips tests, no JARs)
- Configuration cache automatically optimizes repeated builds
- Compiler uses 2GB heap with G1GC for faster compilation
- File system watching enabled for better incremental builds
- Frontend build uses Gradle cache and incremental compilation for faster rebuilds

### JAR Creation
- **Development builds** (`build`, `assemble`) skip JAR creation for speed
- **Explicit JAR creation**: `./gradlew shadowJar` when needed
- **CI/Release builds**: `CI=true ./gradlew build` includes JAR automatically
- **Force JAR in build**: `./gradlew -PenableShadowJar build`
- **Frontend assets**: Automatically included in JAR under `mop-web/` resources

## Versioning

The project uses automatic versioning based on git tags. Version numbers are derived dynamically from the git repository state:

### Version Behavior
- **Clean Release**: `0.12.1` (when on exact git tag)
- **Development Build**: `0.12.1-30-g77dcc897` (30 commits after tag 0.12.1, commit hash 77dcc897)
- **Dirty Working Directory**: `0.12.1-30-g77dcc897-SNAPSHOT` (uncommitted changes present)
- **No Git Available**: `0.0.0-UNKNOWN` (fallback for CI environments without git)
- **BuildInfo class**: Contains `version` field with current version string
- `./gradlew printVersion` - Print the current version number

### Creating Releases
To create a new release:
1. **Tag the commit**: `git tag v0.13.0`
2. **Build will automatically use clean version**: `0.13.0`
3. **Push tag to trigger release**: `git push origin v0.13.0`
4. **Wait for GitHub Actions** to complete (creates release + uploads JAR)
5. **Update JBang catalog locally**:
   ```bash
   # Uses latest git tag automatically
   ./scripts/update-jbang-catalog.sh

   # Or specify version explicitly
   ./scripts/update-jbang-catalog.sh v0.13.0
   ```
6. **Commit and push catalog update**:
   ```bash
   git add jbang-catalog.json
   git commit -m "Update JBang catalog for release v0.13.0"
   git push
   ```

No manual version updates needed - everything is derived from git tags automatically.

## Distribution with jDeploy

The project uses jDeploy to create native application installers for all platforms. jDeploy packages the shadow JAR into platform-specific installers and handles distribution via npm.

### jDeploy Configuration

Configuration is defined in the root `package.json`:
- **JAR Source**: `app/build/libs/brokk-*.jar` (shadow JAR)
- **Java Version**: 21 (required)
- **JavaFX**: Enabled for GUI support
- **JVM Args**: `--add-modules jdk.incubator.vector` for Vector API
- **No Bundled JDK**: Uses system Java installation

### Local jDeploy Usage

```bash
# 1. Build the shadow JAR first
./gradlew shadowJar

# 2. Get the current git version (same as CI does)
VERSION=$(git describe --tags --exact-match HEAD 2>/dev/null || git describe --tags --always --dirty=-SNAPSHOT)

# 3. Update both version and JAR path in package.json
JAR_FILE=$(find app/build/libs -name "brokk-*.jar" | head -1)
jq '.version = "'$VERSION'" | .jdeploy.jar = "'$JAR_FILE'"' package.json > package.tmp.json && mv package.tmp.json package.json

# 4. Install jDeploy if not installed
npm install -g jdeploy

# 5. Package with jDeploy
jdeploy package

# 6. Test the packaged application
./jdeploy-bundle/jdeploy.js

# Alternative: Install locally for testing (links to PATH)
jdeploy install
# Then run from anywhere: brokk

# Alternative: Test JAR directly without jDeploy
java --add-modules jdk.incubator.vector -jar app/build/libs/brokk-*.jar

# Optional: Build native installers locally
# Define your target platforms (customize as needed):
TARGETS="mac-x64,mac-arm64,win,linux"  # Available: mac-x64, mac-arm64, win, linux

# Add bundles/installers to package.json:
jq --argjson targets "$(echo $TARGETS | jq -R 'split(",")')" '.jdeploy.bundles = $targets | .jdeploy.installers = $targets' package.json > package.tmp.json && mv package.tmp.json package.json

# Then package with installers
jdeploy package

# Generated files will be in:
# - jdeploy/bundles/ - Platform-specific app bundles for selected targets
# - jdeploy/installers/ - Native installers for selected targets
```

### Release Process Integration

jDeploy runs automatically in CI/CD during releases:

1. **Triggered by**: Git tag pushes (`v1.0.0`, `1.0.0-beta`, etc.)
2. **Workflows**: Both `jdeploy.yml` and `release.yml` workflows
3. **Process**:
   - Build shadow JAR with Gradle
   - Update `package.json` with exact JAR filename
   - Run jDeploy to create installers
   - Upload installers as GitHub release assets

### Prerelease Handling

jDeploy detects pre-releases based on semantic versioning:
- **Prerelease**: `1.0.0-alpha`, `1.0.0-beta.1`, `1.0.0-rc.1`
- **Stable**: `1.0.0`, `2.1.0`

Prereleases are marked appropriately in GitHub releases and distribution channels.

## Performance Baseline Testing

The TreeSitterRepoRunner provides comprehensive performance analysis for TreeSitter analyzers across major open source projects.

For complete documentation, see [baseline-testing.md](baseline-testing.md).

### Quick Start
```bash
# Get help and see available commands
scripts/run-treesitter-repos.sh help

# Set up test projects (downloads major projects)
scripts/run-treesitter-repos.sh setup

# Test specific project with specific language
scripts/run-treesitter-repos.sh openjdk-java --max-files 500
```

## Icon Browser

To explore available Look and Feel icons for UI development:
- GUI browser: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil icons"`
- Console list: `./gradlew run --args="io.github.jbellis.brokk.gui.SwingIconUtil"`

Use `SwingUtil.uiIcon("IconName")` to safely load icons with automatic fallbacks.

## Theme System

The application uses a unified theme system that synchronizes colors between Java Swing components and the frontend web interface.

### Theme Generation Workflow

1. **Java Theme Definition**: Colors are defined in `ThemeColors.java` with separate maps for dark and light themes
2. **CSS Generation**: Run `./gradlew generateThemeCss` to generate CSS variables from Java colors
3. **Frontend Integration**: Generated CSS is written to `frontend-mop/src/styles/theme-colors.generated.scss`

### Key Files

- **`app/src/main/java/io/github/jbellis/brokk/gui/mop/ThemeColors.java`** - Master theme color definitions
- **`app/src/main/java/io/github/jbellis/brokk/tools/GenerateThemeCss.java`** - CSS generation tool
- **`frontend-mop/src/styles/theme-colors.generated.scss`** - Auto-generated CSS variables

### Adding New Theme Colors

1. Add color to appropriate theme map in `ThemeColors.java`:
   ```java
   DARK_COLORS.put("my_new_color", Color.decode("#123456"));
   LIGHT_COLORS.put("my_new_color", Color.decode("#654321"));
   ```

2. Regenerate CSS variables:
   ```bash
   ./gradlew generateThemeCss
   ```

3. Use in frontend SCSS:
   ```scss
   .my-component {
     color: var(--my-new-color);
   }
   ```

### Theme Naming Conventions

- Use snake_case in Java: `inline_code_color`
- Converts to kebab-case in CSS: `--inline-code-color`
- Colors are automatically sorted alphabetically in generated CSS

## Environment Variables

### BRK_NO_LSP

Controls whether the Java Language Server (JDT LSP) is started. When disabled, the app runs in TSA-only mode (TreeSitter
analyzers only) which improves startup time and reduces memory usage, but advanced Java analysis (call graph, usages,
linting via JDT) will not be available.

- Name: BRK_NO_LSP
- Type: Boolean (case-insensitive)
- Recognized truthy values: 1, true, t, yes, y, on
- Recognized falsy values: 0, false, f, no, n, off
- Empty string: treated as true (disables LSP)
- Unrecognized values: defaults to true (disables LSP) and logs a warning
- Unset: treated as false (LSP enabled)

Notes:
- Parsing is case-insensitive and uses Locale.ROOT.
- When disabled, a message is logged and the JDT LSP is not started.
- Methods relying on JDT capabilities degrade gracefully and return empty/no-op results.

Examples:
```bash
# Disable LSP (preferred explicit)
export BRK_NO_LSP=true

# Disable LSP (any of these are equivalent)
export BRK_NO_LSP=1
export BRK_NO_LSP=YES
export BRK_NO_LSP=on

# Enable LSP explicitly
export BRK_NO_LSP=false
export BRK_NO_LSP=0
export BRK_NO_LSP=off

# Unset => LSP enabled (default)
unset BRK_NO_LSP

# Empty or invalid => LSP disabled (with warning on invalid)
export BRK_NO_LSP=""
export BRK_NO_LSP="maybe"  # logs warning, disables LSP
```
