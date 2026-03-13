package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.models.Constants.LineType;

import edu.hm.hafner.util.FilteredLog;

import java.util.Map;
import java.util.Set;

/**
 * Creates GitLab commit comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations.
 *
 * @author Ullrich Hafner
 */
class GitLabCommitCommentBuilder extends GitLabCommentBuilder {
    private final long projectId;
    private final String sha;

    GitLabCommitCommentBuilder(final CommitsApi commitsApi, final Map<String, Set<Integer>> modifiedFiles,
            final long projectId, final String sha, final String workingDirectory, final FilteredLog log) {
        super(commitsApi, modifiedFiles, log, workingDirectory);

        this.projectId = projectId;
        this.sha = sha;
    }

    @Override
    @SuppressWarnings("checkstyle:ParameterNumber")
    protected boolean createComment(final CommentType commentType, final String relativePath,
            final int lineStart, final int lineEnd,
            final String message, final String title,
            final int columnStart, final int columnEnd,
            final String details, final String markDownDetails) {
        if (showCommentsInCommit()) {
            try {
                var markdownMessage = createMarkdownMessage(commentType, relativePath, lineStart,
                        lineEnd, columnStart, columnEnd, title, message, markDownDetails, this::getEnv);

                getCommitsApi().addComment(projectId, sha, markdownMessage, relativePath, adjustLine(lineStart), LineType.NEW);

                return true;
            }
            catch (GitLabApiException exception) {
                getLog().logException(exception, "Can't create commit comment for %s", relativePath);
            }
        }
        return false;
    }
}
