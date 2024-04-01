package edu.hm.hafner.grading.gitlab;

import java.util.function.Function;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.CommitsApi;

import edu.hm.hafner.grading.CommentBuilder;
import edu.hm.hafner.util.FilteredLog;
import edu.hm.hafner.util.VisibleForTesting;

/**
 * Base class for comment builders that publish comments to GitLab.
 *
 * @author Ullrich Hafner
 */
abstract class GitLabCommentBuilder extends CommentBuilder {
    private final FilteredLog log;
    private final CommitsApi commitsApi;
    private int maxCoverageComments;
    private int maxWarningComments;

    @VisibleForTesting
    GitLabCommentBuilder() {
        this(null, new FilteredLog("Errors"));
    }

    GitLabCommentBuilder(final CommitsApi commitsApi, final FilteredLog log, final String... prefixesToRemove) {
        super(prefixesToRemove);

        this.commitsApi = commitsApi;
        this.log = log;

        maxWarningComments = getIntegerEnvironment("MAX_WARNING_COMMENTS");
        log.logInfo(">>>> MAX_WARNING_COMMENTS: ", getMaxWarningComments());

        maxCoverageComments = getIntegerEnvironment("MAX_COVERAGE_COMMENTS");
        log.logInfo(">>>> MAX_COVERAGE_COMMENTS: ", getMaxCoverageComments());
    }

    @Override
    protected int getMaxWarningComments() {
        return maxWarningComments;
    }

    @Override
    protected int getMaxCoverageComments() {
        return maxCoverageComments;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    protected String createMarkdownMessage(final CommentType commentType, final String relativePath,
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

    protected String createRange(final char prefix, final int start, final int end) {
        if (start < 1) {
            return StringUtils.EMPTY;
        }
        var single = String.valueOf(prefix) + start;
        if (end <= start) {
            return single;
        }
        return single + "-" + prefix + end;
    }

    private String getIcon(final CommentType commentType) {
        return switch (commentType) {
            case WARNING -> "warning";
            case NO_COVERAGE, PARTIAL_COVERAGE -> "footprints";
            default -> "microscope";
        };
    }

    @VisibleForTesting
    String createLinesAndColumns(final String range, final int columnStart, final int columnEnd) {
        var columns = createRange('C', columnStart, columnEnd);
        if (columns.isBlank()) {
            return "(%s)".formatted(range);
        }
        return "(%s:%s)".formatted(range, columns);
    }

    private int getIntegerEnvironment(final String key) {
        try {
            return Integer.parseInt(getEnv(key));
        }
        catch (NumberFormatException exception) {
            getLog().logError(">>>> Error: no integer value");

            return Integer.MAX_VALUE;
        }
    }

    protected FilteredLog getLog() {
        return log;
    }

    protected CommitsApi getCommitsApi() {
        return commitsApi;
    }

    String getEnv(final String name) {
        return StringUtils.defaultString(System.getenv(name));
    }
}
