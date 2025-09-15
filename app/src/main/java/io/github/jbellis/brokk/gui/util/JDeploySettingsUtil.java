package io.github.jbellis.brokk.gui.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.util.Json;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Utility to update JDeploy configuration files (package.json) with JVM memory settings. */
public class JDeploySettingsUtil {
    private static final Logger logger = LogManager.getLogger(JDeploySettingsUtil.class);

    private JDeploySettingsUtil() {
        // utility
    }

    private record PathWithTime(Path path, FileTime mtime) {}

    /**
     * Update all JDeploy package.json files under ~/.jdeploy/gh-packages/*.brokk with the desired JVM memory settings.
     *
     * <p>Logic: - locate gh-packages directory - find a child directory ending with ".brokk" - search recursively for
     * package.json files - read/modify jdeploy.args to remove existing -Xmx... args, then add the desired -Xmx if
     * manual - write back to disk
     */
    public static void updateJvmMemorySettings(MainProject.JvmMemorySettings settings) {
        Objects.requireNonNull(settings, "settings");

        var home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            logger.warn("Cannot determine user.home; skipping JDeploy update");
            return;
        }

        var ghPackagesDir = Path.of(home, ".jdeploy", "gh-packages");
        if (!Files.isDirectory(ghPackagesDir)) {
            logger.info("JDeploy packages directory not found: {}; skipping JDeploy update", ghPackagesDir);
            return;
        }

        var brokkDirOpt = findBrokkDir(ghPackagesDir);
        if (brokkDirOpt.isEmpty()) {
            logger.info("No '.brokk' directory found under {}; skipping JDeploy update", ghPackagesDir);
            return;
        }

        var brokkDir = brokkDirOpt.get();
        logger.debug("Updating JDeploy settings under {}", brokkDir);

        List<Path> packageJsonFiles;
        try (Stream<Path> stream = Files.walk(brokkDir, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            packageJsonFiles = stream.filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().equals("package.json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to traverse for package.json under {}", brokkDir, e);
            return;
        }

        if (packageJsonFiles.isEmpty()) {
            logger.info("No package.json files found under {}; nothing to update", brokkDir);
            return;
        }

        var mapper = Json.getMapper();
        for (var pkg : packageJsonFiles) {
            try {
                updateSinglePackageJson(mapper, pkg, settings);
            } catch (Exception e) {
                logger.warn("Failed to update JVM memory settings in {}", pkg, e);
            }
        }
    }

    private static Optional<Path> findBrokkDir(Path ghPackagesDir) {
        try (Stream<Path> children = Files.list(ghPackagesDir)) {
            return children.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith(".brokk"))
                    .map(p -> new PathWithTime(p, getMtimeSafe(p)))
                    .sorted(Comparator.comparing(PathWithTime::mtime).reversed())
                    .map(PathWithTime::path)
                    .findFirst();
        } catch (IOException e) {
            logger.warn("Error listing {}", ghPackagesDir, e);
            return Optional.empty();
        }
    }

    private static FileTime getMtimeSafe(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static void updateSinglePackageJson(
            ObjectMapper mapper, Path packageJson, MainProject.JvmMemorySettings settings) throws IOException {

        logger.trace("Processing {}", packageJson);

        var typeRef = new TypeReference<Map<String, Object>>() {};
        Map<String, Object> root;
        try (var in = Files.newInputStream(packageJson)) {
            root = mapper.readValue(in, typeRef);
        }

        if (root == null) {
            root = new LinkedHashMap<>();
        }

        Map<String, Object> jdeploy = getOrCreateChildMap(root, "jdeploy");
        List<String> args = getOrCreateStringList(jdeploy, "args");

        // Remove any existing -Xmx* arguments
        args.removeIf(s -> s.startsWith("-Xmx"));

        // Add desired setting if manual
        if (!settings.automatic()) {
            int mb = settings.manualMb();
            if (mb > 0) {
                var xmx = "-Xmx" + mb + "m";
                args.add(xmx);
                logger.info("Set {}: added {}", packageJson, xmx);
            } else {
                logger.info("Manual memory was non-positive ({} MB) for {}; skipping add", mb, packageJson);
            }
        } else {
            logger.info("Automatic memory selected; removed any existing -Xmx from {}", packageJson);
        }

        // Persist back
        try (var out = Files.newOutputStream(packageJson)) {
            mapper.writeValue(out, root);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOrCreateChildMap(Map<String, Object> parent, String key) {
        var child = parent.get(key);
        if (child instanceof Map<?, ?> map) {
            // Ensure it is mutable and has String keys
            var newMap = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> newMap.put(String.valueOf(k), v));
            // Replace in case original is immutable or not LinkedHashMap
            parent.put(key, newMap);
            return newMap;
        } else {
            var created = new LinkedHashMap<String, Object>();
            parent.put(key, created);
            return created;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> getOrCreateStringList(Map<String, Object> map, String key) {
        var val = map.get(key);
        if (val instanceof List<?> list) {
            var result = new ArrayList<String>(list.size());
            for (var o : list) {
                if (o != null) {
                    result.add(String.valueOf(o));
                }
            }
            map.put(key, result);
            return result;
        } else {
            var created = new ArrayList<String>();
            map.put(key, created);
            return created;
        }
    }
}
