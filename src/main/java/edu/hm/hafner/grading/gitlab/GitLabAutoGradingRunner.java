package edu.hm.hafner.grading.gitlab;

import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;

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

            String projectId = getEnv("CI_PROJECT_ID", log);
            if (projectId.isBlank() || !StringUtils.isNumeric(projectId)) {
                log.logError("No valid CI_PROJECT_ID found - skipping");

                return;
            }

            var project = gitLabApi.getProjectApi().getProject(39165L);

            String sha = getEnv("CI_COMMIT_SHA", log);
            if (sha.isBlank()) {
                log.logError("No valid CI_COMMIT_SHA found - skipping");

                return;
            }

            var report = new GradingReport();
            var comment = report.getMarkdownSummary(score, ":mortar_board: Quality Status");
            gitLabApi.getCommitsApi().addComment(project.getId(), sha, comment);
        }
        catch (GitLabApiException exception) {
            throw new IllegalStateException("Can't connect to GitLab", exception);
        }
    }

    private String getEnv(final String key, final FilteredLog log) {
        String value = StringUtils.defaultString(System.getenv(key));
        log.logInfo(">>>> " + key + ": " + value);
        return value;
    }
}
