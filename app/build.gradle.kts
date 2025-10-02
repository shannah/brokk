import net.ltgt.gradle.errorprone.errorprone
import java.time.Duration
import org.gradle.process.CommandLineArgumentProvider

plugins {
    java
    application
    alias(libs.plugins.errorprone)
    alias(libs.plugins.shadow)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.spotless)
    alias(libs.plugins.javafx)
    alias(libs.plugins.node)
}

group = "io.github.jbellis"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("io.github.jbellis.brokk.Brokk")
    applicationDefaultJvmArgs = listOf(
        // enable feature flags; JavaExec baseline supplies other args
        "-Dbrokk.servicetiers=true",
        "-Dbrokk.architectshell=true"
    )
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.swing", "javafx.web")
}

node {
    version.set(libs.versions.nodejs.get())
    npmVersion.set(libs.versions.npm.get())
    download.set(true)
    workDir.set(file("${project.rootDir}/.gradle/nodejs"))
    npmWorkDir.set(file("${project.rootDir}/.gradle/npm"))
    nodeProjectDir.set(file("${project.rootDir}/frontend-mop"))
}

repositories {
    mavenCentral()
    // Additional repositories for dependencies
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
    }
    maven {
        url = uri("https://www.jetbrains.com/intellij-repository/releases")
    }
}

dependencies {
    // NullAway - version must match local jar version
    implementation(libs.nullaway)

    implementation(libs.okhttp)

    implementation(libs.jtokkit)
    implementation(libs.jlama.core)

    // Console and logging
    implementation(libs.bundles.logging)

    // Utilities
    implementation(libs.bundles.ui)
    implementation(libs.java.diff.utils)
    implementation(libs.jackson.databind)
    implementation(libs.jspecify)
    implementation(libs.picocli)
    implementation(libs.bundles.jediterm)
    implementation(libs.bundles.apache)
    implementation(libs.bundles.jdkmon)
    implementation(libs.disklrucache)
    implementation(libs.uuid.creator)
    implementation(libs.mcp.sdk)
    // For JSON serialization interfaces (used by CodeUnit)
    api(libs.jackson.annotations)

    // Markdown and templating
    implementation(libs.bundles.markdown)

    // GitHub API
    implementation(libs.github.api)
    implementation(libs.jsoup)

    // JGit and SSH
    implementation(libs.bundles.git)

    // TreeSitter parsers
    implementation(libs.bundles.treesitter)

    // Eclipse LSP
    implementation(libs.bundles.eclipse.lsp)

    // Java Decompiler
    implementation(libs.java.decompiler)

    // Maven Resolver for dependency import
    implementation(libs.bundles.maven.resolver)

    implementation(libs.checker.util)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testImplementation(libs.jupiter.iface)
    testRuntimeOnly(libs.bundles.junit.runtime)

    // Error Prone and NullAway for null safety checking
    "errorprone"(files("libs/error_prone_core-brokk_build-with-dependencies.jar"))
    "errorprone"(libs.nullaway)
    "errorprone"(libs.dataflow.errorprone)
    compileOnly(libs.checker.qual)
}

// Force version computation at configuration time
val actualVersion = project.rootProject.version.toString().ifEmpty {
    // Fallback: read from cache file
    val versionCacheFile = File(project.rootDir, "build/version.txt")
    if (versionCacheFile.exists()) {
        val lines = versionCacheFile.readLines()
        if (lines.size >= 2) lines[1] else "0.0.0-UNKNOWN"
    } else {
        "0.0.0-UNKNOWN"
    }
}

buildConfig {
    buildConfigField("String", "version", "\"$actualVersion\"")
    packageName("io.github.jbellis.brokk")
    className("BuildInfo")
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("frontendInstall") {
    args.set(listOf("install", "--silent"))
    inputs.file("${project.rootDir}/frontend-mop/package.json")
    inputs.file("${project.rootDir}/frontend-mop/package-lock.json")
    outputs.dir("${project.rootDir}/frontend-mop/node_modules")
    outputs.cacheIf { true }
}

tasks.register("frontendPatch") {
    dependsOn("frontendInstall")

    inputs.dir("${project.rootDir}/frontend-mop/node_modules/svelte-exmarkdown").optional(true)
    outputs.file("${project.rootDir}/frontend-mop/node_modules/svelte-exmarkdown/package.json").optional(true)

    doLast {
        val packageJsonFile = file("${project.rootDir}/frontend-mop/node_modules/svelte-exmarkdown/package.json")
        if (packageJsonFile.exists()) {
            var content = packageJsonFile.readText()
            content = content.replace("\"./dist/contexts.d.ts\"", "\"./dist/contexts.svelte.d.ts\"")
            content = content.replace("\"./dist/contexts.js\"", "\"./dist/contexts.svelte.js\"")
            packageJsonFile.writeText(content)
        }
    }
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("frontendBuild") {
    description = "Build frontend with Vite"
    group = "frontend"
    dependsOn("frontendInstall", "frontendPatch")

    args.set(listOf("run", "build"))

    inputs.dir("${project.rootDir}/frontend-mop/src")
    inputs.file("${project.rootDir}/frontend-mop/package.json")
    inputs.file("${project.rootDir}/frontend-mop/vite.config.mjs")
    inputs.file("${project.rootDir}/frontend-mop/vite.worker.config.mjs")
    inputs.file("${project.rootDir}/frontend-mop/tsconfig.json")
    inputs.file("${project.rootDir}/frontend-mop/tsconfig.node.json")
    inputs.file("${project.rootDir}/frontend-mop/index.html")
    inputs.file("${project.rootDir}/frontend-mop/dev.html")

    outputs.dir("${project.projectDir}/src/main/resources/mop-web")
}

tasks.register<Delete>("frontendClean") {
    description = "Clean frontend build artifacts"
    group = "frontend"
    delete("${project.projectDir}/src/main/resources/mop-web")
    delete("${project.rootDir}/frontend-mop/node_modules")
    delete("${project.rootDir}/frontend-mop/.gradle")
}

// Handle duplicate files in JAR
tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Common ErrorProne JVM exports for JDK 16+
val errorProneJvmArgs = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
)

// Baseline JVM args provider; composes with applicationDefaultJvmArgs and other providers
val baselineJvmArgsProvider = object : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-ea",  // Enable assertions
        "--add-modules=jdk.incubator.vector",
        "-Dbrokk.devmode=true"
    )
}

// Configure main source compilation with ErrorProne/NullAway
tasks.named<JavaCompile>("compileJava") {
    options.isIncremental = true
    options.isFork = true
    options.forkOptions.jvmArgs?.addAll(errorProneJvmArgs + listOf(
        "-Xmx2g",  // Increase compiler heap size
        "-XX:+UseG1GC"  // Use G1 GC for compiler
    ))

    // Optimized javac options for performance
    options.compilerArgs.addAll(listOf(
        "-parameters",  // Preserve method parameter names
        "-g:source,lines,vars",  // Generate full debugging information
        "-Xmaxerrs", "500",  // Maximum error count
        "-XDcompilePolicy=simple",  // Error Prone compilation policy
        "--should-stop=ifError=FLOW",  // Stop compilation policy
        "-Werror",  // Treat warnings as errors
        "-Xlint:deprecation,unchecked"  // Combined lint warnings for efficiency
    ))

    // Enhanced ErrorProne configuration with NullAway
    options.errorprone {
        // Disable specific Error Prone checks that are handled in SBT config
        disable("FutureReturnValueIgnored")
        disable("MissingSummary")
        disable("EmptyBlockTag")
        disable("NonCanonicalType")

        // Enable NullAway with comprehensive configuration
        error("NullAway")

        // Exclude dev/ directory from all ErrorProne checks
        excludedPaths = ".*/src/main/java/(dev/|eu/).*"

        // Core NullAway options
        option("NullAway:AnnotatedPackages", "io.github.jbellis.brokk")
        option("NullAway:ExcludedFieldAnnotations",
               "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll,org.junit.jupiter.api.Test")
        option("NullAway:ExcludedClassAnnotations",
               "org.junit.jupiter.api.extension.ExtendWith,org.junit.jupiter.api.TestInstance")
        option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
        option("NullAway:JarInferStrictMode", "true")
        option("NullAway:CheckOptionalEmptiness", "true")
        option("NullAway:KnownInitializers",
               "org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll")
        option("NullAway:HandleTestAssertionLibraries", "true")
        option("NullAway:ExcludedPaths", ".*/src/main/java/dev/.*")
        option("RedundantNullCheck:CheckRequireNonNull", "true")

        // RedundantNullCheck
        enable("RedundantNullCheck")
    }
}

// Configure test compilation without ErrorProne
tasks.named<JavaCompile>("compileTestJava") {
    options.isIncremental = true
    options.isFork = false
    options.compilerArgs.addAll(listOf(
        "-parameters",
        "-g:source,lines,vars",
        "-Xlint:deprecation",
        "-Xlint:unchecked"
    ))

    // Completely disable ErrorProne for test compilation
    options.errorprone.isEnabled = false
}

tasks.withType<JavaExec>().configureEach {
    // Baseline JVM args provided lazily; composes with applicationDefaultJvmArgs and other plugins
    jvmArgumentProviders.add(baselineJvmArgsProvider)
}


tasks.withType<Test> {
    useJUnitPlatform()

    // Use a single forked JVM for all tests (for TreeSitter native library isolation)
    // On Windows, use only 1 fork to avoid CI issues; on other platforms use 6
    maxParallelForks = if (System.getProperty("os.name").lowercase().contains("windows")) 1 else 6
    forkEvery = 0  // Never fork new JVMs during test execution

    jvmArgs = listOf(
        "-ea",  // Enable assertions
        "--add-modules=jdk.incubator.vector",
        "-Dbrokk.devmode=true",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=./build/test-heap-dumps/"
    )

    // Test execution settings
    testLogging {
        events("passed", "skipped")  // Only show passed/skipped during execution
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = false
        showCauses = false
        showStackTraces = false
        showStandardStreams = false
    }

    // Collect failed tests and their output for end summary
    val failedTests = mutableListOf<String>()
    val testOutputs = mutableMapOf<String, String>()

    // Capture test output for failed tests
    addTestOutputListener(object : TestOutputListener {
        override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) {
            val testKey = "${testDescriptor.className}.${testDescriptor.name}"
            if (outputEvent.destination == TestOutputEvent.Destination.StdOut ||
                outputEvent.destination == TestOutputEvent.Destination.StdErr) {
                testOutputs.merge(testKey, outputEvent.message) { existing, new -> existing + new }
            }
        }
    })

    // Capture individual test failures for later reporting
    afterTest(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (result.resultType == TestResult.ResultType.FAILURE) {
            val testKey = "${desc.className}.${desc.name}"
            val errorMessage = result.exception?.message ?: "Unknown error"
            val stackTrace = result.exception?.stackTrace?.joinToString("\n") { "      at $it" } ?: ""
            val output = testOutputs[testKey]?.let { "\n   Output:\n$it" } ?: ""
            failedTests.add("❌ $testKey\n   Error: $errorMessage\n$stackTrace$output")
        }
    }))

    // Show all failures grouped at the end
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent == null) { // Only execute once for the root suite
            if (result.failedTestCount > 0) {
                println("\n" + "=".repeat(80))
                println("FAILED TESTS SUMMARY")
                println("=".repeat(80))
                failedTests.forEach { failure ->
                    println("\n$failure")
                }
                println("\n" + "=".repeat(80))
                println("Total tests: ${result.testCount}")
                println("Passed: ${result.successfulTestCount}")
                println("Failed: ${result.failedTestCount}")
                println("Skipped: ${result.skippedTestCount}")
                println("=".repeat(80))
            }
        }
    }))

    // Fail fast on first test failure
    failFast = false

    // Test timeout
    timeout.set(Duration.ofMinutes(30))

    // System properties for tests
    systemProperty("brokk.test.mode", "true")
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Runs the Brokk CLI"
    mainClass.set("io.github.jbellis.brokk.cli.BrokkCli")
    classpath = sourceSets.main.get().runtimeClasspath
    if (project.hasProperty("args")) {
        args((project.property("args") as String).split(" "))
    }
}

tasks.register<JavaExec>("runSkeletonPrinter") {
    group = "application"
    description = "Runs the SkeletonPrinter tool"
    mainClass.set("io.github.jbellis.brokk.tools.SkeletonPrinter")
    classpath = sourceSets.test.get().runtimeClasspath
    if (project.hasProperty("args")) {
        args((project.property("args") as String).split(" "))
    }
}

tasks.register<JavaExec>("generateThemeCss") {
    group = "application"
    description = "Generates theme CSS variables from ThemeColors"
    mainClass.set("io.github.jbellis.brokk.tools.GenerateThemeCss")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("${project.rootDir}/frontend-mop/src/styles/theme-colors.generated.scss")
}

tasks.register<JavaExec>("runTreeSitterRepoRunner") {
    group = "application"
    description = "Runs the TreeSitterRepoRunner tool for TreeSitter performance analysis"
    mainClass.set("io.github.jbellis.brokk.tools.TreeSitterRepoRunner")
    classpath = sourceSets.test.get().runtimeClasspath
    // Additional JVM args specific to repository runner; baseline adds -ea and -Dbrokk.devmode=true
    jvmArgumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf(
            "-Xmx8g",
            "-XX:+UseZGC",
            "-XX:+UnlockExperimentalVMOptions"
        )
    })
    if (project.hasProperty("args")) {
        args((project.property("args") as String).split(" "))
    }
}

tasks.shadowJar {
    archiveBaseName.set("brokk")
    archiveClassifier.set("")
    mergeServiceFiles()
    isZip64 = true  // Enable zip64 for large archives

    // Assembly merge strategy equivalent
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer::class.java)

    // Exclude signature files
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/MANIFEST.MF")

    manifest {
        attributes["Main-Class"] = "io.github.jbellis.brokk.Brokk"
    }
}

tasks.named("compileJava") {
    dependsOn("generateBuildConfig")
}

tasks.named("processResources") {
    dependsOn("frontendBuild")
}

tasks.named("clean") {
    dependsOn("frontendClean")
}

// Disable script and distribution generation since we don't need them
tasks.named("startScripts") {
    enabled = false
}

tasks.named("startShadowScripts") {
    enabled = false
}

tasks.named("distTar") {
    enabled = false
}

tasks.named("distZip") {
    enabled = false
}

tasks.named("shadowDistTar") {
    enabled = false
}

tasks.named("shadowDistZip") {
    enabled = false
}

// Ensure the main jar task runs before shadowJar to establish proper dependencies
tasks.shadowJar {
    dependsOn(tasks.jar)
    mustRunAfter(tasks.jar)
}

// Only run shadowJar when explicitly requested or in CI
tasks.shadowJar {
    enabled = project.hasProperty("enableShadowJar") ||
              System.getenv("CI") == "true" ||
              gradle.startParameter.taskNames.contains("shadowJar")
}

// When shadowJar is enabled, disable the regular jar task to avoid creating two JARs
tasks.jar {
    enabled = !tasks.shadowJar.get().enabled
}
