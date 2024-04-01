package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.Constants.LineType;
import org.gitlab4j.api.GitLabApiException;

import edu.hm.hafner.util.FilteredLog;

/**
 * Creates GitLab commit comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations.
 *
 * @author Ullrich Hafner
 */
class GitLabCommitCommentBuilder extends GitLabCommentBuilder {
    private final long projectId;
    private final String sha;

    GitLabCommitCommentBuilder(final CommitsApi commitsApi, final long projectId, final String sha,
            final String workingDirectory, final FilteredLog log) {
        super(commitsApi, log, workingDirectory);

        this.projectId = projectId;
        this.sha = sha;
    }

    @Override
    @SuppressWarnings("checkstyle:ParameterNumber")
    protected void createComment(final CommentType commentType, final String relativePath,
            final int lineStart, final int lineEnd,
            final String message, final String title,
            final int columnStart, final int columnEnd,
            final String details, final String markDownDetails) {
        try {
            var markdownMessage = createMarkdownMessage(commentType, relativePath, lineStart,
                    lineEnd, columnStart, columnEnd, title, message, markDownDetails, this::getEnv);

            getCommitsApi().addComment(projectId, sha, markdownMessage, relativePath, lineStart, LineType.NEW);
        }
        catch (GitLabApiException exception) {
            getLog().logException(exception, "Can't create commit comment for %s", relativePath);
        }
    }
}
