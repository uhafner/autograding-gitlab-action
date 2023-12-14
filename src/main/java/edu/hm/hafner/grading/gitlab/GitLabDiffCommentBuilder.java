package edu.hm.hafner.grading.gitlab;

import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.gitlab4j.api.models.Position;

import edu.hm.hafner.grading.CommentBuilder;

/**
 * Creates GitLab commit comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations.
 *
 * @author Ullrich Hafner
 */
class GitLabDiffCommentBuilder extends CommentBuilder {
    private final DiscussionsApi discussionsApi;
    private final MergeRequest mergeRequest;
    private final MergeRequestVersion lastVersion;

    GitLabDiffCommentBuilder(final DiscussionsApi discussionsApi, final MergeRequest mergeRequest, final MergeRequestVersion lastVersion, final String workingDirectory) {
        super(workingDirectory);

        this.discussionsApi = discussionsApi;
        this.mergeRequest = mergeRequest;
        this.lastVersion = lastVersion;
    }

    @Override
    @SuppressWarnings("checkstyle:ParameterNumber")
    protected void createComment(final CommentType commentType, final String relativePath, final int lineStart, final int lineEnd,
            final String message, final String title,
            final int columnStart, final int columnEnd, final String details) {
        try {
            var position = new Position()
                    .withBaseSha(lastVersion.getBaseCommitSha())
                    .withHeadSha(lastVersion.getHeadCommitSha())
                    .withStartSha(lastVersion.getStartCommitSha())
                    .withNewLine(lineStart)
                    .withOldPath(relativePath)
                    .withNewPath(relativePath)
                    .withPositionType(Position.PositionType.TEXT);
            var markdownMessage = createMarkdownMessage(commentType, title, message, details);
            discussionsApi.createMergeRequestDiscussion(mergeRequest.getProjectId(), mergeRequest.getIid(),
                    markdownMessage, null, null, position);
        }
        catch (GitLabApiException exception) {
            // ignore exceptions and continue
        }
    }

    static String createMarkdownMessage(final CommentType commentType, final String title, final String message, final String details) {
        return String.format("#### :%s: %s%n%n%s", getIcon(commentType), title, message)
                + (details.isBlank() ? StringUtils.EMPTY : "\n\n" + details);
    }

    private static String getIcon(final CommentType commentType) {
        return switch (commentType) {
            case WARNING -> "warning";
            case NO_COVERAGE, PARTIAL_COVERAGE -> "footprints";
            default -> "microscope";
        };
    }
}
