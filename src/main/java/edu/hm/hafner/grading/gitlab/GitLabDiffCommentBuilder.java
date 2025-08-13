package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.gitlab4j.api.models.Position;
import org.gitlab4j.models.Constants.LineType;

import edu.hm.hafner.util.FilteredLog;

/**
 * Creates GitLab merge request comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations. If the comment cannot be created on the merge request, then a comment is created on the commit.
 *
 * @author Ullrich Hafner
 */
class GitLabDiffCommentBuilder extends GitLabCommentBuilder {
    private final DiscussionsApi discussionsApi;
    private final MergeRequest mergeRequest;
    private final MergeRequestVersion lastVersion;

    GitLabDiffCommentBuilder(final CommitsApi commitsApi, final DiscussionsApi discussionsApi, final MergeRequest mergeRequest,
            final MergeRequestVersion lastVersion, final String workingDirectory, final FilteredLog log) {
        super(commitsApi, log, workingDirectory);

        this.discussionsApi = discussionsApi;
        this.mergeRequest = mergeRequest;
        this.lastVersion = lastVersion;
    }

    @Override
    @SuppressWarnings("checkstyle:ParameterNumber")
    protected void createComment(final CommentType commentType, final String relativePath,
            final int lineStart, final int lineEnd,
            final String message, final String title,
            final int columnStart, final int columnEnd,
            final String details, final String markDownDetails) {
        var sha = lastVersion.getHeadCommitSha();
        var position = new Position()
                .withBaseSha(lastVersion.getBaseCommitSha())
                .withHeadSha(sha)
                .withStartSha(lastVersion.getStartCommitSha())
                .withNewPath(relativePath)
                .withNewLine(adjustLine(lineStart))
                .withPositionType(Position.PositionType.TEXT);
        var markdownMessage = createMarkdownMessage(commentType, relativePath, lineStart, lineEnd, columnStart,
                columnEnd, title, message, markDownDetails, this::getEnv);
        try {
            discussionsApi.createMergeRequestDiscussion(
                    mergeRequest.getProjectId(),
                    mergeRequest.getIid(),
                    markdownMessage, null, null, position);
        }
        catch (GitLabApiException exception) { // If the comment is on a file or position not part of the diff
            getLog().logException(exception, "Can't create merge request comment for %s in #%d", relativePath, mergeRequest.getIid());

            if (showCommentsInCommit()) {
                try {
                    getCommitsApi().addComment(mergeRequest.getProjectId(), sha, markdownMessage, relativePath,
                            lineStart, LineType.NEW);
                }
                catch (GitLabApiException ignored) {
                    getLog().logException(exception, "Can't create commit comment for %s", relativePath);
                }
            }
        }
    }
}
