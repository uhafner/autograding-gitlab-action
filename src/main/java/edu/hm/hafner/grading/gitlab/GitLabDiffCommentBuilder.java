package edu.hm.hafner.grading.gitlab;

import java.util.function.Function;

import org.apache.commons.io.FilenameUtils;
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

    GitLabDiffCommentBuilder(final DiscussionsApi discussionsApi, final MergeRequest mergeRequest,
            final MergeRequestVersion lastVersion, final String workingDirectory) {
        super(workingDirectory);

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
        try {
            var position = new Position()
                    .withBaseSha(lastVersion.getBaseCommitSha())
                    .withHeadSha(lastVersion.getHeadCommitSha())
                    .withStartSha(lastVersion.getStartCommitSha())
                    .withNewLine(lineStart)
                    .withOldPath(relativePath)
                    .withNewPath(relativePath)
                    .withPositionType(Position.PositionType.TEXT);
            var markdownMessage = createMarkdownMessage(commentType, relativePath,
                    lineStart, lineEnd, columnStart, columnEnd,
                    title, message, markDownDetails);
            discussionsApi.createMergeRequestDiscussion(mergeRequest.getProjectId(), mergeRequest.getIid(),
                    markdownMessage, null, null, position);
        }
        catch (GitLabApiException exception) {
            // ignore exceptions and continue
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    static String createMarkdownMessage(final CommentType commentType, final String relativePath,
            final int lineStart, final int lineEnd, final int columnStart, final int columnEnd,
            final String title, final String message, final String details) {
        return createMarkdownMessage(commentType, relativePath, lineStart, lineEnd, columnStart, columnEnd, title,
                message, details, GitLabDiffCommentBuilder::getEnv);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    static String createMarkdownMessage(final CommentType commentType, final String relativePath,
            final int lineStart, final int lineEnd, final int columnStart, final int columnEnd,
            final String title, final String message, final String details,
            final Function<String, String> environment) {
        var linkName = FilenameUtils.getName(relativePath);
        var projectUrl = environment.apply("CI_PROJECT_URL");
        var commitSha = environment.apply("CI_COMMIT_SHA");
        var linkUrl = "%s/blob/%s/%s".formatted(projectUrl, commitSha, relativePath);

        var range = createRange('L', lineStart, lineEnd);
        if (!range.isBlank()) {
            linkUrl += "#" + range;
            linkName += createLinesAndColumns(range, columnStart, columnEnd);
        }
        var link = "[%s](%s)".formatted(linkName, linkUrl);

        return String.format("#### :%s: &nbsp; %s%n%n%s: %s", getIcon(commentType), title, link, message)
                + (details.isBlank() ? StringUtils.EMPTY : "\n\n" + details);
    }

    static String createLinesAndColumns(final String range, final int columnStart, final int columnEnd) {
        var columns = createRange('C', columnStart, columnEnd);
        if (columns.isBlank()) {
            return "(%s)".formatted(range);
        }
        return "(%s:%s)".formatted(range, columns);
    }

    static String getEnv(final String name) {
        return StringUtils.defaultString(System.getenv(name));
    }

    static String createRange(final char prefix, final int start, final int end) {
        if (start < 1) {
            return StringUtils.EMPTY;
        }
        var single = String.valueOf(prefix) + start;
        if (end <= start) {
            return single;
        }
        return single + "-" + prefix + end;
    }

    private static String getIcon(final CommentType commentType) {
        return switch (commentType) {
            case WARNING -> "warning";
            case NO_COVERAGE, PARTIAL_COVERAGE -> "footprints";
            default -> "microscope";
        };
    }
}
