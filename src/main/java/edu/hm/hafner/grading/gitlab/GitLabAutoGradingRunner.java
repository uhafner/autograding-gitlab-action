package edu.hm.hafner.grading.gitlab;

import java.util.logging.Level;

import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.gitlab4j.api.models.Note;
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
    static final String AUTOGRADING_MARKER = "<!-- -[autograding-gitlab-action]- -->";

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
    protected String getDisplayName() {
        return "GitLab Autograding";
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
            gitLabApi.enableRequestResponseLogging(Level.FINE, 4096);

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

            grade(score, gitLabApi, project, sha, log);
        }
        catch (GitLabApiException exception) {
            log.logException(exception, "Error while accessing GitLab API");
        }
    }

    private void grade(final AggregatedScore score, final GitLabApi gitLabApi, final Project project, final String sha,
            final FilteredLog log) throws GitLabApiException {
        var report = new GradingReport();
        var comment = getEnv("SKIP_DETAILS", log).isEmpty()
                ? report.getMarkdownDetails(score, getTitleName())
                : report.getMarkdownSummary(score, getTitleName());
        comment = AUTOGRADING_MARKER + "\n\n" + comment + "\n\nCreated by " + getAutogradingVersionLink(log);
        String mergeRequestEnvironment = getEnv("CI_MERGE_REQUEST_IID", log);
        if (mergeRequestEnvironment.isBlank() || !StringUtils.isNumeric(mergeRequestEnvironment)) {
            createLineCommentsOnCommit(score, log, gitLabApi, project, sha);

            createCommentOnCommit(gitLabApi, project, sha, comment);
        }
        else {
            var mergeRequestId = Long.parseLong(mergeRequestEnvironment);

            deleteExistingComments(gitLabApi, project, mergeRequestId, log);

            var versions = gitLabApi.getMergeRequestApi()
                    .getDiffVersions(project.getId(), mergeRequestId);
            if (versions.isEmpty()) {
                log.logInfo("Diff versions are empty, adding line comments to commit");
                createLineCommentsOnCommit(score, log, gitLabApi, project, sha);
            }
            else {
                log.logInfo("Diff versions found, adding line comments to merge request diff");
                var mergeRequest = gitLabApi.getMergeRequestApi()
                        .getMergeRequest(project.getId(), mergeRequestId);
                createLineCommentsOnDiff(score, log, gitLabApi.getCommitsApi(), gitLabApi.getDiscussionsApi(),
                        mergeRequest, versions.get(0));
            }

            createCommentOnMergeRequest(gitLabApi, project, mergeRequestEnvironment, comment, log);
        }
        log.logInfo("GitLab Action has finished");
    }

    private String getAutogradingVersionLink(final FilteredLog log) {
        var version = readVersion(log);
        var sha = readSha(log);
        return "[%s](https://github.com/uhafner/autograding-gitlab-action/releases/tag/v%s) v%s (#%s)"
                .formatted(getDisplayName(), version, version, sha);
    }

    private String getTitleName() {
        return StringUtils.defaultIfBlank(System.getenv("DISPLAY_NAME"), "Autograding score");
    }

    private void createLineCommentsOnDiff(final AggregatedScore score, final FilteredLog log,
            final CommitsApi commitsApi, final DiscussionsApi discussionsApi, final MergeRequest mergeRequest,
            final MergeRequestVersion lastVersion) {
        if (canCreateLineComments(log)) {
            var annotationBuilder = new GitLabDiffCommentBuilder(commitsApi, discussionsApi, mergeRequest, lastVersion,
                    getWorkingDirectory(log), log);
            annotationBuilder.createAnnotations(score);
        }
        else {
            log.logInfo("Skipping line comments on merge request diff");
        }
    }

    private void createLineCommentsOnCommit(final AggregatedScore score, final FilteredLog log, final GitLabApi gitLabApi,
            final Project project, final String sha) {
        if (canCreateLineComments(log)) {
            var commentBuilder = new GitLabCommitCommentBuilder(gitLabApi.getCommitsApi(), project.getId(), sha,
                    getWorkingDirectory(log), log);
            commentBuilder.createAnnotations(score);
        }
        else {
            log.logInfo("Skipping line comments on commit");
        }
    }

    private String getWorkingDirectory(final FilteredLog log) {
        return getEnv("CI_PROJECT_DIR", log) + "/";
    }

    private boolean canCreateLineComments(final FilteredLog log) {
        return getEnv("SKIP_LINE_COMMENTS", log).isEmpty();
    }

    private void deleteExistingComments(final GitLabApi gitLabApi, final Project project,
            final long mergeRequestId, final FilteredLog log) throws GitLabApiException {
        var projectId = project.getId();

        log.logInfo("Deleting old auto-grading notes");
        gitLabApi.getNotesApi()
                .getMergeRequestNotes(projectId, mergeRequestId).stream()
                .filter(note -> note.getBody().startsWith(AUTOGRADING_MARKER))
                .forEach(note -> delete(gitLabApi, note, projectId, mergeRequestId));
    }

    private void createCommentOnMergeRequest(final GitLabApi gitLabApi, final Project project, final String mergeRequestId,
            final String comment, final FilteredLog log) throws GitLabApiException {
        var projectId = project.getId();
        var mergeRequestIid = Long.parseLong(mergeRequestId);

        log.logInfo("Creating merge request note");
        gitLabApi.getNotesApi().createMergeRequestNote(projectId, mergeRequestIid, comment);
    }

    private void delete(final GitLabApi gitLabApi, final Note note,
            final Long projectId, final long mergeRequestIid) {
        try {
            gitLabApi.getNotesApi().deleteMergeRequestNote(projectId, mergeRequestIid, note.getId());
        }
        catch (GitLabApiException exception) {
            // ignore exceptions
        }
    }

    private void createCommentOnCommit(final GitLabApi gitLabApi, final Project project, final String sha, final String comment)
            throws GitLabApiException {
        gitLabApi.getCommitsApi().addComment(project.getId(), sha, comment);
    }

    private static String getEnv(final String key, final FilteredLog log) {
        String value = StringUtils.defaultString(System.getenv(key));
        log.logInfo(">>>> " + key + ": " + value);
        return value;
    }
}
