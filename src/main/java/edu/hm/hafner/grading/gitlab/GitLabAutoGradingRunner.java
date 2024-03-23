package edu.hm.hafner.grading.gitlab;

import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.gitlab4j.api.models.Project;

import edu.hm.hafner.grading.AggregatedScore;
import edu.hm.hafner.grading.AutoGradingRunner;
import edu.hm.hafner.grading.GradingReport;
import edu.hm.hafner.util.FilteredLog;

/**
 * GitLab action entrypoint for the autograding action.
 *
 * @author Ullrich Hafner
 */
public class GitLabAutoGradingRunner extends AutoGradingRunner {
    /**
     * Public entry point, calls the action.
     *
     * @param unused
     *         not used
     */
    public static void main(final String... unused) {
        new GitLabAutoGradingRunner().run();
    }

    @Override
    protected void publishGradingResult(final AggregatedScore score, final FilteredLog log) {
        var gitlabUrl = getEnv("CI_SERVER_URL", log);
        if (StringUtils.isBlank(gitlabUrl)) {
            log.logError("No CI_SERVER_URL defined - skipping");

            return;
        }
        String oAuthToken = getEnv("GITLAB_TOKEN", log);
        if (oAuthToken.isBlank()) {
            log.logError("No valid GITLAB_TOKEN found - skipping");

            return;
        }

        try (GitLabApi gitLabApi = new GitLabApi(gitlabUrl, oAuthToken)) {
            gitLabApi.setRequestTimeout(1000, 2000);
            gitLabApi.enableRequestResponseLogging();

            String projectId = getEnv("CI_PROJECT_ID", log);
            if (projectId.isBlank() || !StringUtils.isNumeric(projectId)) {
                log.logError("No valid CI_PROJECT_ID found - skipping");

                return;
            }

            String sha = getEnv("CI_COMMIT_SHA", log);
            if (sha.isBlank()) {
                log.logError("No valid CI_COMMIT_SHA found - skipping");

                return;
            }

            var project = gitLabApi.getProjectApi().getProject(Long.parseLong(projectId));
            grade(score, log, gitLabApi, project, sha);
        }
        catch (GitLabApiException exception) {
            log.logException(exception, "Error while accessing GitLab API");
        }
    }

    private void grade(final AggregatedScore score, final FilteredLog log, final GitLabApi gitLabApi,
            final Project project, final String sha) throws GitLabApiException {
        var report = new GradingReport();
        var comment = getEnv("SKIP_DETAILS", log).isEmpty()
                ? report.getMarkdownDetails(score, getTitleName())
                : report.getMarkdownSummary(score, getTitleName());

        String mergeRequestId = getEnv("CI_MERGE_REQUEST_IID", log);
        if (mergeRequestId.isBlank() || !StringUtils.isNumeric(mergeRequestId)) {
            createCommentOnCommit(gitLabApi, project, sha, comment);
            createLineCommentsOnCommit(score, log, gitLabApi, project, sha);
        }
        else {
            createCommentOnMergeRequest(gitLabApi, project, mergeRequestId, comment);

            var versions = gitLabApi.getMergeRequestApi()
                    .getDiffVersions(project.getId(), Long.parseLong(mergeRequestId));
            if (versions.isEmpty()) {
                createLineCommentsOnCommit(score, log, gitLabApi, project, sha);
            }
            else {
                var mergeRequest = gitLabApi.getMergeRequestApi()
                        .getMergeRequest(project.getId(), Long.parseLong(mergeRequestId));
                createLineCommentsOnDiff(score, log, gitLabApi.getDiscussionsApi(), mergeRequest, versions.get(0));
            }
        }
        log.logInfo("GitLab Action has finished");
    }

    private String getTitleName() {
        return StringUtils.defaultIfBlank(System.getenv("DISPLAY_NAME"), "Autograding score");
    }

    private void createLineCommentsOnDiff(final AggregatedScore score, final FilteredLog log,
            final DiscussionsApi discussionsApi, final MergeRequest mergeRequest,
            final MergeRequestVersion lastVersion) {
        if (canCreateLineComments(log)) {
            var annotationBuilder = new GitLabDiffCommentBuilder(discussionsApi, mergeRequest, lastVersion,
                    getWorkingDirectory(log));
            annotationBuilder.createAnnotations(score);
        }
    }

    private String getWorkingDirectory(final FilteredLog log) {
        return getEnv("CI_PROJECT_DIR", log) + "/";
    }

    private void createLineCommentsOnCommit(final AggregatedScore score, final FilteredLog log, final GitLabApi gitLabApi,
            final Project project, final String sha) {
        if (canCreateLineComments(log)) {
            var commentBuilder = new GitLabCommitCommentBuilder(gitLabApi.getCommitsApi(), project.getId(), sha,
                    getWorkingDirectory(log));
            commentBuilder.createAnnotations(score);
        }
    }

    private boolean canCreateLineComments(final FilteredLog log) {
        return getEnv("SKIP_LINE_COMMENTS", log).isEmpty();
    }

    private void createCommentOnMergeRequest(final GitLabApi gitLabApi, final Project project, final String mergeRequestId,
            final String comment) throws GitLabApiException {
        gitLabApi.getNotesApi()
                .createMergeRequestNote(project.getId(), Long.parseLong(mergeRequestId), comment);
    }

    private void createCommentOnCommit(final GitLabApi gitLabApi, final Project project, final String sha, final String comment)
            throws GitLabApiException {
        gitLabApi.getCommitsApi().addComment(project.getId(), sha, comment);
    }

    static String getEnv(final String key, final FilteredLog log) {
        String value = StringUtils.defaultString(System.getenv(key));
        log.logInfo(">>>> " + key + ": " + value);
        return value;
    }
}
