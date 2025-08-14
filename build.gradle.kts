// Get the version from the latest git tag and current version with caching
fun getVersionFromGit(): String {
    val versionCacheFile = File(rootDir, "build/version.txt")

    try {
        // Get current git HEAD (works for both regular repos and worktrees)
        val currentGitHead = getCurrentGitHead()

        // Check if we can use cached version
        if (versionCacheFile.exists() && currentGitHead != null) {
            val cacheLines = versionCacheFile.readLines()
            if (cacheLines.size >= 2) {
                val cachedGitHead = cacheLines[0]
                val cachedVersion = cacheLines[1]

                // If git HEAD hasn't changed, use cached version
                if (cachedGitHead == currentGitHead) {
                    return cachedVersion
                }
            }
        }

        // Calculate version from git
        val version = calculateVersionFromGit()

        // Cache the result
        if (currentGitHead != null) {
            versionCacheFile.parentFile.mkdirs()
            versionCacheFile.writeText("$currentGitHead\n$version\n")
        }

        return version
    } catch (e: Exception) {
        return "0.0.0-UNKNOWN"
    }
}

fun getCurrentGitHead(): String? {
    return try {
        val gitHeadProcess = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(rootDir)
            .start()
        gitHeadProcess.waitFor()
        if (gitHeadProcess.exitValue() == 0) {
            gitHeadProcess.inputStream.bufferedReader().readText().trim()
        } else null
    } catch (e: Exception) {
        null
    }
}

fun calculateVersionFromGit(): String {
    return try {
        // First, try to get exact tag match with version pattern
        val exactTagProcess = ProcessBuilder("git", "describe", "--tags", "--exact-match", "--match", "[0-9]*", "HEAD")
            .directory(rootDir)
            .start()
        exactTagProcess.waitFor()

        if (exactTagProcess.exitValue() == 0) {
            // On exact tag - clean release version
            exactTagProcess.inputStream.bufferedReader().readText().trim()
        } else {
            // Not on exact tag - get development version with version tags only
            val devVersionProcess = ProcessBuilder("git", "describe", "--tags", "--always", "--match", "[0-9]*", "--dirty=-SNAPSHOT")
                .directory(rootDir)
                .start()
            devVersionProcess.waitFor()
            devVersionProcess.inputStream.bufferedReader().readText().trim()
        }
    } catch (e: Exception) {
        "0.0.0-UNKNOWN"
    }
}

plugins {
    alias(libs.plugins.dependency.analysis)
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                // the objective is to get this to fail, i.e. don't allow failing dependencies
                severity("warn")
            }
        }
    }
}

allprojects {
    group = "io.github.jbellis"
    version = getVersionFromGit()

    repositories {
        mavenCentral()
    }
}

tasks.register("printVersion") {
    description = "Prints the current project version"
    group = "help"
    doLast {
        println(version)
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.autonomousapps.dependency-analysis")

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


    tasks.withType<Test> {
        filter {
            isFailOnNoMatchingTests = false
        }
    }
}
