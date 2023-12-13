package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.Constants.LineType;
import org.gitlab4j.api.GitLabApiException;

import edu.hm.hafner.grading.CommentBuilder;

/**
 * Creates GitLab commit comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations.
 *
 * @author Ullrich Hafner
 */
public class GitLabCommentBuilder extends CommentBuilder {
    private final CommitsApi commitsApi;
    private final Long projectId;
    private final String sha;

    /**
     * Creates GitLab Git commit comments for static analysis warnings, for lines with missing coverage, and for lines
     * with survived mutations.
     *
     * @param commitsApi
     *         the GitLab API to use to write commit comments
     * @param projectId
     *         the project ID in GitLab
     * @param sha
     *         the commit ID in Git
     * @param workingDirectory
     *         the working directory of the GitLab job, needs to be removed from the file names
     */
    public GitLabCommentBuilder(final CommitsApi commitsApi, final long projectId, final String sha,
            final String workingDirectory) {
        super(workingDirectory);

        this.commitsApi = commitsApi;
        this.projectId = projectId;
        this.sha = sha;
    }

    @Override
    @SuppressWarnings("checkstyle:ParameterNumber")
    protected void createComment(final String relativePath, final int lineStart, final int lineEnd,
            final String message, final String title,
            final int columnStart, final int columnEnd, final String details) {
        try {
            commitsApi.addComment(projectId, sha, message, relativePath, lineStart, LineType.NEW);
        }
        catch (GitLabApiException exception) {
            // ignore exceptions
        }
    }
}
