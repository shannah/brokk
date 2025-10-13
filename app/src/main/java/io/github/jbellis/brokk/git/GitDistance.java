package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.VisibleForTesting;
import java.nio.file.Path;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Provides the logic to perform a Git-centric distance calculations for given type declarations. */
public final class GitDistance {
    private static final Logger logger = LogManager.getLogger(GitDistance.class);
    private static final int COMMITS_TO_PROCESS = 1_000;

    /** Represents an edge between two CodeUnits in the co-occurrence graph. */
    public record FileEdge(ProjectFile src, ProjectFile dst) {}

    /**
     * Point-wise Mutual Information (PMI) distance.
     *
     * <p>p(X,Y) = |C(X) ∩ C(Y)| / |Commits| p(X) = |C(X)| / |Commits| PMI = log2( p(X,Y) / (p(X)·p(Y)) )
     *
     * @return a sorted list of files with relevance scores. If no seed weights are given,an empty result.
     */
    public static List<IAnalyzer.FileRelevance> getPMI(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) {

        if (seedWeights.isEmpty()) {
            return List.of();
        }

        try {
            return computePmiScores(repo, seedWeights, k, reversed);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    public static List<IAnalyzer.FileRelevance> getMostImportantFilesScored(GitRepo repo, int k)
            throws GitAPIException {
        var commits = repo.listCommitsDetailed(repo.getCurrentBranch(), COMMITS_TO_PROCESS);
        var scores = computeImportanceScores(repo, commits);
        logger.info("Computed importance scores for getMostImportantFilesScored: {}", scores);

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    public static List<ProjectFile> getMostImportantFiles(GitRepo repo, int k) throws GitAPIException {
        return getMostImportantFilesScored(repo, k).stream()
                .map(IAnalyzer.FileRelevance::file)
                .toList();
    }

    /**
     * Fetches commits that modified any of the specified files, limited to maxResults.
     *
     * @param repo the Git repository wrapper.
     * @param files the collection of files to fetch commits for.
     * @param maxResults the maximum number of commits to fetch per file.
     * @return a sorted list of unique commits (newest first).
     */
    static List<CommitInfo> getCommitsForFiles(GitRepo repo, Collection<ProjectFile> files, int maxResults) {
        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            return pool.submit(() -> files.parallelStream()
                            .flatMap(file -> {
                                try {
                                    return repo.getFileHistory(file, maxResults).stream();
                                } catch (GitAPIException e) {
                                    throw new RuntimeException("Error getting file history for " + file, e);
                                }
                            })
                            .distinct()
                            .sorted((a, b) -> b.date().compareTo(a.date()))
                            .toList())
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error getting file history in parallel", e);
        }
    }

    /**
     * Sorts a collection of files by their importance using Git history analysis. The importance is determined by
     * analyzing change frequency and recency across the pooled commit histories of all provided files.
     *
     * <p>This method first collects all commits that modified any of the input files, then applies the same
     * time-weighted scoring algorithm as {@link #getMostImportantFilesScored} to rank them.
     *
     * @param files the collection of files to sort by importance.
     * @param repo the Git repository wrapper.
     * @return the input files sorted by importance (most important first).
     */
    public static List<ProjectFile> sortByImportance(Collection<ProjectFile> files, IGitRepo repo) {
        if (!(repo instanceof GitRepo gr)) {
            return List.copyOf(files);
        }

        var commits = getCommitsForFiles(gr, files, Integer.MAX_VALUE);
        Map<ProjectFile, Double> scores;
        try {
            scores = computeImportanceScores(gr, commits);
            logger.info("Computed importance scores for sortByImportance: {}", scores);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }

        // Score-less files sort to the end
        return files.stream()
                .sorted((a, b) -> {
                    double sb = scores.getOrDefault(b, Double.NEGATIVE_INFINITY);
                    double sa = scores.getOrDefault(a, Double.NEGATIVE_INFINITY);
                    return Double.compare(sb, sa);
                })
                .toList();
    }

    /**
     * Personalized PageRank over the co-change graph with Inverse Fan-Out Edges (IFOE) and half-life recency decay.
     *
     * Graph construction:
     *   For each commit with m>1 distinct files, each ordered pair (u->v, u!=v) receives weight:
     *     w_edge = decay(commit) * 1/(m-1)
     *   where decay(commit) = 0.5^( age(commit) / halfLife ), and halfLife is a constant (e.g., 90 days).
     *
     * Teleport vector (topic-sensitive):
     *   π(u) ∝ sum over commits that touched u of decay(commit).
     *   If no touches are recorded, π is uniform.
     *
     * Dangling handling:
     *   Rank mass from dangling nodes is redistributed uniformly (1/n).
     *
     * No explicit seed weights; all seeds are treated equally via π. Recency is applied consistently to both edges and π.
     */
    private static Map<ProjectFile, Double> computeImportanceScores(GitRepo repo, List<CommitInfo> commits)
            throws GitAPIException {
        if (commits.isEmpty()) return Map.of();

        var newest = commits.stream().map(CommitInfo::date).max(java.util.Comparator.naturalOrder()).orElseThrow();
        var halfLife = java.time.Duration.ofDays(30);

        var adj = new java.util.concurrent.ConcurrentHashMap<ProjectFile, java.util.concurrent.ConcurrentHashMap<ProjectFile, Double>>();
        var nodes = java.util.concurrent.ConcurrentHashMap.<ProjectFile>newKeySet();
        var decayedTouches = new java.util.concurrent.ConcurrentHashMap<ProjectFile, Double>();

        try (var pool = new java.util.concurrent.ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                try {
                    var changed = repo.listFilesChangedInCommit(commit.id());
                    if (changed.isEmpty()) return;
                    var distinct = changed.stream().distinct().toList();
                    nodes.addAll(distinct);

                    double ageSec = java.time.Duration.between(commit.date(), newest).toSeconds();
                    double decay = Math.pow(0.5, ageSec / Math.max(1, halfLife.toSeconds()));

                    for (var f : distinct) decayedTouches.merge(f, decay, Double::sum);

                    int m = distinct.size();
                    if (m < 2) return;
                    double wBase = decay / (m - 1);

                    for (int i = 0; i < m; i++) {
                        var u = distinct.get(i);
                        var out = adj.computeIfAbsent(u, k -> new java.util.concurrent.ConcurrentHashMap<>());
                        for (int j = 0; j < m; j++) {
                            if (i == j) continue;
                            var v = distinct.get(j);
                            out.merge(v, wBase, Double::sum);
                        }
                    }
                } catch (GitAPIException e) {
                    throw new RuntimeException("Error processing commit: " + commit.id(), e);
                }
            })).get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("Error building co-change graph in parallel", e);
        }

        var all = new java.util.HashSet<>(nodes);
        if (all.isEmpty()) return Map.of();

        var outWeight = new java.util.HashMap<ProjectFile, Double>(all.size());
        for (var u : all) {
            var outs = adj.get(u);
            double sum = 0.0;
            if (outs != null) for (var val : outs.values()) sum += val;
            outWeight.put(u, sum);
        }

        double totalTouch = decayedTouches.values().stream().mapToDouble(Double::doubleValue).sum();
        var pi = new java.util.HashMap<ProjectFile, Double>(all.size());
        if (totalTouch > 0.0) {
            for (var u : all) pi.put(u, decayedTouches.getOrDefault(u, 0.0) / totalTouch);
        } else {
            double uni = 1.0 / all.size();
            for (var u : all) pi.put(u, uni);
        }

        int n = all.size();
        var rank = new java.util.HashMap<ProjectFile, Double>(n);
        double init = 1.0 / n;
        for (var u : all) rank.put(u, init);

        final double d = 0.85;
        final int maxIters = 50;
        final double eps = 1e-6;

        for (int it = 0; it < maxIters; it++) {
            double danglingMass = 0.0;
            for (var v : all) if (outWeight.getOrDefault(v, 0.0) == 0.0) danglingMass += rank.get(v);

            var next = new java.util.HashMap<ProjectFile, Double>(n);
            double uniformLeak = d * danglingMass / n;
            for (var u : all) next.put(u, (1.0 - d) * pi.get(u) + uniformLeak);

            for (var v : all) {
                double outSum = outWeight.getOrDefault(v, 0.0);
                if (outSum == 0.0) continue;
                var outs = adj.get(v);
                if (outs == null || outs.isEmpty()) continue;
                double share = d * rank.get(v) / outSum;
                for (var e : outs.entrySet()) {
                    next.merge(e.getKey(), share * e.getValue(), Double::sum);
                }
            }

            double delta = 0.0;
            for (var u : all) delta += Math.abs(next.get(u) - rank.get(u));
            rank = next;
            if (delta < eps) break;
        }

        return rank;
    }

    private static List<IAnalyzer.FileRelevance> computePmiScores(
            GitRepo repo, Map<ProjectFile, Double> seedWeights, int k, boolean reversed) throws GitAPIException {
        var commits = getCommitsForFiles(repo, seedWeights.keySet(), COMMITS_TO_PROCESS);
        var totalCommits = commits.size();
        if (totalCommits == 0) return List.of();

        var fileCounts = new ConcurrentHashMap<ProjectFile, Integer>();
        var jointCounts = new ConcurrentHashMap<FileEdge, Integer>();

        try (var pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors())) {
            pool.submit(() -> commits.parallelStream().forEach(commit -> {
                        try {
                            var changedFiles = repo.listFilesChangedInCommit(commit.id());
                            if (changedFiles.isEmpty()) return;

                            // individual counts
                            for (var f : changedFiles) {
                                fileCounts.merge(f, 1, Integer::sum);
                            }

                            // joint counts
                            var seedsInCommit = changedFiles.stream()
                                    .filter(seedWeights::containsKey)
                                    .collect(Collectors.toSet());
                            if (seedsInCommit.isEmpty()) return;

                            for (var seed : seedsInCommit) {
                                for (var cu : changedFiles) {
                                    jointCounts.merge(new FileEdge(seed, cu), 1, Integer::sum);
                                }
                            }
                        } catch (GitAPIException e) {
                            throw new RuntimeException("Error processing commit: " + commit.id(), e);
                        }
                    }))
                    .get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Error computing PMI scores in parallel", e);
        }

        if (jointCounts.isEmpty()) return List.of();

        var scores = new HashMap<ProjectFile, Double>();
        for (var entry : jointCounts.entrySet()) {
            var seed = entry.getKey().src();
            var target = entry.getKey().dst();
            var joint = entry.getValue();

            final var countSeed = requireNonNull(fileCounts.get(seed));
            final int countTarget = requireNonNull(fileCounts.get(target));
            if (countSeed == 0 || countTarget == 0 || joint == 0) continue;

            double pmi = Math.log((double) joint * totalCommits / ((double) countSeed * countTarget)) / Math.log(2);

            double weight = seedWeights.getOrDefault(seed, 0.0);
            if (weight == 0.0) continue;

            scores.merge(target, weight * pmi, Double::sum);
        }

        return scores.entrySet().stream()
                .map(e -> new IAnalyzer.FileRelevance(e.getKey(), e.getValue()))
                .sorted((a, b) ->
                        reversed ? Double.compare(a.score(), b.score()) : Double.compare(b.score(), a.score()))
                .limit(k)
                .toList();
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: GitDistance <path-to-git-repo>");
            System.exit(1);
        }

        var repoPath = Path.of(args[0]);
        logger.info("Analyzing most important files for repository: {}", repoPath);

        try {
            var repo = new GitRepo(repoPath);
            var results = getMostImportantFilesScored(repo, 20);
            results.forEach(fr ->
                    System.out.printf("%s\t%.6f%n", fr.file().getFileName(), fr.score()));
        } catch (GitAPIException e) {
            logger.error("Error computing most important files for repo {}: {}", repoPath, e.getMessage(), e);
            System.exit(2);
        }
    }
}
