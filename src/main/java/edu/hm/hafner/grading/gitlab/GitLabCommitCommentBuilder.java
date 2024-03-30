package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.Constants.LineType;
import org.gitlab4j.api.GitLabApiException;

import edu.hm.hafner.grading.CommentBuilder;
import edu.hm.hafner.util.FilteredLog;

/**
 * Creates GitLab commit comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations.
 *
 * @author Ullrich Hafner
 */
class GitLabCommitCommentBuilder extends CommentBuilder {
    private final CommitsApi commitsApi;
    private final Long projectId;
    private final String sha;
    private final FilteredLog log;

    GitLabCommitCommentBuilder(final CommitsApi commitsApi, final long projectId, final String sha,
            final String workingDirectory, final FilteredLog log) {
        super(workingDirectory);

        this.commitsApi = commitsApi;
        this.projectId = projectId;
        this.sha = sha;
        this.log = log;
    }

    @Override
    @SuppressWarnings("checkstyle:ParameterNumber")
    protected void createComment(final CommentType commentType, final String relativePath,
            final int lineStart, final int lineEnd,
            final String message, final String title,
            final int columnStart, final int columnEnd,
            final String details, final String markDownDetails) {
        try {
            var markdownMessage = GitLabDiffCommentBuilder.createMarkdownMessage(commentType, relativePath, lineStart,
                    lineEnd, columnStart, columnEnd, title, message, markDownDetails, GitLabDiffCommentBuilder::getEnv);

            commitsApi.addComment(projectId, sha, markdownMessage, relativePath, lineStart, LineType.NEW);
        }
        catch (GitLabApiException exception) {
            log.logException(exception, "Can't create commit comment for %s", relativePath);
        }
    }
}
