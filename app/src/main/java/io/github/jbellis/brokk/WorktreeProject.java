package io.github.jbellis.brokk;

import com.jakewharton.disklrucache.DiskLruCache;
import io.github.jbellis.brokk.MainProject.DataRetentionPolicy;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.analyzer.Language;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class WorktreeProject extends AbstractProject {
    private final MainProject parent;

    public WorktreeProject(Path root, MainProject parent) {
        super(root);
        this.parent = parent;
    }

    @Override
    public MainProject getParent() {
        return parent;
    }

    @Override
    public MainProject getMainProject() {
        return parent;
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        return parent.getAnalyzerLanguages();
    }

    @Override
    public void setAnalyzerLanguages(Set<Language> languages) {
        parent.setAnalyzerLanguages(languages);
    }

    @Override
    public BuildAgent.BuildDetails loadBuildDetails() {
        return parent.loadBuildDetails();
    }

    @Override
    public void saveBuildDetails(BuildAgent.BuildDetails details) {
        parent.saveBuildDetails(details);
    }

    @Override
    public CompletableFuture<BuildAgent.BuildDetails> getBuildDetailsFuture() {
        return parent.getBuildDetailsFuture();
    }

    @Override
    public BuildAgent.BuildDetails awaitBuildDetails() {
        return parent.awaitBuildDetails();
    }

    @Override
    public boolean hasBuildDetails() {
        return parent.hasBuildDetails();
    }

    @Override
    public DataRetentionPolicy getDataRetentionPolicy() {
        return parent.getDataRetentionPolicy();
    }

    @Override
    public void setDataRetentionPolicy(DataRetentionPolicy policy) {
        parent.setDataRetentionPolicy(policy);
    }

    @Override
    public String getStyleGuide() {
        return parent.getStyleGuide();
    }

    @Override
    public void saveStyleGuide(String styleGuide) {
        parent.saveStyleGuide(styleGuide);
    }

    @Override
    public String getReviewGuide() {
        return parent.getReviewGuide();
    }

    @Override
    public void saveReviewGuide(String reviewGuide) {
        parent.saveReviewGuide(reviewGuide);
    }

    @Override
    public Service.ModelConfig getArchitectModelConfig() {
        return parent.getArchitectModelConfig();
    }

    @Override
    public void setArchitectModelConfig(Service.ModelConfig config) {
        parent.setArchitectModelConfig(config);
    }

    @Override
    public Service.ModelConfig getCodeModelConfig() {
        return parent.getCodeModelConfig();
    }

    @Override
    public void setCodeModelConfig(Service.ModelConfig config) {
        parent.setCodeModelConfig(config);
    }

    @Override
    public void setAskModelConfig(Service.ModelConfig config) {
        parent.setAskModelConfig(config);
    }

    @Override
    public Service.ModelConfig getSearchModelConfig() {
        return parent.getSearchModelConfig();
    }

    @Override
    public void setSearchModelConfig(Service.ModelConfig config) {
        parent.setSearchModelConfig(config);
    }

    @Override
    public boolean isDataShareAllowed() {
        return parent.isDataShareAllowed();
    }

    @Override
    public String getCommitMessageFormat() {
        return parent.getCommitMessageFormat();
    }

    @Override
    public void setCommitMessageFormat(String format) {
        parent.setCommitMessageFormat(format);
    }

    @Override
    public CodeAgentTestScope getCodeAgentTestScope() {
        return parent.getCodeAgentTestScope();
    }

    @Override
    public boolean isGitHubRepo() {
        return parent.isGitHubRepo();
    }

    @Override
    public boolean isGitIgnoreSet() {
        return parent.isGitIgnoreSet();
    }

    @Override
    public void setCodeAgentTestScope(CodeAgentTestScope selectedScope) {
        parent.setCodeAgentTestScope(selectedScope);
    }

    @Override
    public io.github.jbellis.brokk.IssueProvider getIssuesProvider() {
        return parent.getIssuesProvider();
    }

    @Override
    public void setIssuesProvider(io.github.jbellis.brokk.IssueProvider provider) {
        parent.setIssuesProvider(provider);
    }

    @Override
    public boolean getArchitectRunInWorktree() {
        return parent.getArchitectRunInWorktree();
    }

    @Override
    public DiskLruCache getDiskCache() {
        return parent.getDiskCache();
    }

    @Override
    public Set<Dependency> getLiveDependencies() {
        return parent.getLiveDependencies();
    }

    @Override
    public void saveLiveDependencies(Set<Path> dependencyTopLevelDirs) {
        parent.saveLiveDependencies(dependencyTopLevelDirs);
    }

    @Override
    public List<List<String>> loadBlitzHistory() {
        return parent.loadBlitzHistory();
    }

    @Override
    public List<List<String>> addToBlitzHistory(String parallel, String post, int maxItems) {
        return parent.addToBlitzHistory(parallel, post, maxItems);
    }

    @Override
    public SessionManager getSessionManager() {
        return parent.getSessionManager();
    }

    @Override
    public void sessionsListChanged() {
        parent.sessionsListChanged();
    }

    @Override
    public boolean getPlanFirst() {
        return parent.getPlanFirst();
    }

    @Override
    public void setPlanFirst(boolean v) {
        parent.setPlanFirst(v);
    }

    @Override
    public boolean getSearch() {
        return parent.getSearch();
    }

    @Override
    public void setSearch(boolean v) {
        parent.setSearch(v);
    }

    @Override
    public boolean getInstructionsAskMode() {
        return parent.getInstructionsAskMode();
    }

    @Override
    public void setInstructionsAskMode(boolean ask) {
        parent.setInstructionsAskMode(ask);
    }
}
