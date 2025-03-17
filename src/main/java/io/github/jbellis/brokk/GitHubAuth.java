package io.github.jbellis.brokk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Handles GitHub authentication and API calls.
 */
public class GitHubAuth
{
    private static final Logger logger = LogManager.getLogger(GitHubAuth.class);

    /**
     * Gets (or prompts for) the GitHub token from the Project workspace, returning a GitHub client.
     * In a real app, you'd do a proper OAuth flow or a secure prompt.
     * Here, we just retrieve the stored token or let the user store one.
     */
    public static GitHub connect(Project project)
            throws IOException
    {
        var token = project.getGitHubToken();
        if (token == null || token.isBlank()) {
            // In a real application, you'd show a dialog or OAuth flow.
            // For now, just log and fail.
            logger.warn("No GitHub token found. Please set one in the workspace properties.");
            throw new IOException("No GitHub token configured.");
        }
        return new GitHubBuilder().withOAuthToken(token).build();
    }
    
    /**
     * Creates an anonymous GitHub connection for accessing public repositories.
     * Use this when no authentication is needed or as fallback when token auth fails.
     */
    public static GitHub connectAnonymously() throws IOException
    {
        return new GitHubBuilder().build();
    }

    /**
     * Fetches the default branch name for the specified repo (owner/repo).
     */
    public static String getDefaultBranch(Project project, String owner, String repoName)
    {
        try {
            var gh = connect(project);
            var repo = gh.getRepository(owner + "/" + repoName);
            return repo.getDefaultBranch();
        } catch (Exception e) {
            logger.warn("Error fetching default branch with auth: {}. Trying anonymous access...", e.getMessage());
            try {
                var gh = connectAnonymously();
                var repo = gh.getRepository(owner + "/" + repoName);
                return repo.getDefaultBranch();
            } catch (Exception e2) {
                logger.error("Error fetching default branch anonymously: {}", e2.getMessage(), e2);
                return "main"; // Fallback to a common default
            }
        }
    }

    /**
     * Lists open pull requests for the specified repo.
     * Attempts to use authentication, but falls back to anonymous access for public repos.
     */
    public static List<GHPullRequest> listOpenPullRequests(Project project, String owner, String repoName)
    {
        try {
            var gh = connect(project);
            var repo = gh.getRepository(owner + "/" + repoName);
            return repo.getPullRequests(GHIssueState.OPEN);
        } catch (Exception e) {
            logger.warn("Error fetching pull requests with auth: {}. Trying anonymous access...", e.getMessage());
            try {
                var gh = connectAnonymously();
                var repo = gh.getRepository(owner + "/" + repoName);
                return repo.getPullRequests(GHIssueState.OPEN);
            } catch (Exception e2) {
                logger.error("Error fetching pull requests anonymously: {}", e2.getMessage(), e2);
                return Collections.emptyList();
            }
        }
    }

    /**
     * Fetches commits in a given pull request.
     * Returns them as GHCommit objects that you can compare to the default branch if needed.
     */
    public static List<GHCommitPointer> listPullRequestCommits(Project project, String owner, String repoName, int prNumber)
    {
        try {
            var gh = connect(project);
            var repo = gh.getRepository(owner + "/" + repoName);
            var pr = repo.getPullRequest(prNumber);
            // Return the HEAD commit pointer for the PR, or you might want to get a compare object
            // For multi-commit detail, you'd use pr.listCommits() for GHCommit objects.
            return List.of(pr.getHead());
        } catch (Exception e) {
            logger.warn("Error fetching PR commits with auth: {}. Trying anonymous access...", e.getMessage());
            try {
                var gh = connectAnonymously();
                var repo = gh.getRepository(owner + "/" + repoName);
                var pr = repo.getPullRequest(prNumber);
                return List.of(pr.getHead());
            } catch (Exception e2) {
                logger.error("Error fetching PR commits anonymously: {}", e2.getMessage(), e2);
                return Collections.emptyList();
            }
        }
    }
}

