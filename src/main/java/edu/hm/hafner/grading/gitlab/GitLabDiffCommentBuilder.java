package edu.hm.hafner.grading.gitlab;

import java.util.function.Function;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.Constants.LineType;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.gitlab4j.api.models.Position;

import edu.hm.hafner.grading.CommentBuilder;
import edu.hm.hafner.util.FilteredLog;

/**
 * Creates GitLab merge request comments for static analysis warnings, for lines with missing coverage, and for lines with
 * survived mutations. If the comment cannot be created on the merge request, then a comment is created on the commit.
 *
 * @author Ullrich Hafner
 */
class GitLabDiffCommentBuilder extends CommentBuilder {
    private final CommitsApi commitsApi;
    private final DiscussionsApi discussionsApi;
    private final MergeRequest mergeRequest;
    private final MergeRequestVersion lastVersion;
    private final FilteredLog log;

    GitLabDiffCommentBuilder(final CommitsApi commitsApi, final DiscussionsApi discussionsApi, final MergeRequest mergeRequest,
            final MergeRequestVersion lastVersion, final String workingDirectory, final FilteredLog log) {
        super(workingDirectory);

        this.commitsApi = commitsApi;
        this.discussionsApi = discussionsApi;
        this.mergeRequest = mergeRequest;
        this.lastVersion = lastVersion;
        this.log = log;
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
                .withNewLine(lineStart)
                .withNewPath(relativePath)
                .withPositionType(Position.PositionType.TEXT);
        var markdownMessage = createMarkdownMessage(commentType, relativePath, lineStart, lineEnd, columnStart,
                columnEnd, title, message, markDownDetails, GitLabDiffCommentBuilder::getEnv);
        try {
            discussionsApi.createMergeRequestDiscussion(
                    mergeRequest.getProjectId(),
                    mergeRequest.getIid(),
                    markdownMessage, null, null, position);
        }
        catch (GitLabApiException exception) { // If the comment is on a file or position not part of the diff
            log.logException(exception, "Can't create merge request comment for %s in #%d", relativePath, mergeRequest.getIid());

            try {
                commitsApi.addComment(mergeRequest.getProjectId(), sha, markdownMessage, relativePath, lineStart, LineType.NEW);
            }
            catch (GitLabApiException ignored) {
                log.logException(exception, "Can't create commit comment for %s", relativePath);
            }
        }
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

        return String.format("%s%n%n#### :%s: &nbsp; %s%n%n%s: %s",
                GitLabAutoGradingRunner.AUTOGRADING_MARKER, getIcon(commentType), title, link, message)
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
