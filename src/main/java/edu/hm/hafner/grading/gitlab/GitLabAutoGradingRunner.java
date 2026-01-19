package edu.hm.hafner.grading.gitlab;

import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Discussion;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.gitlab4j.api.models.Note;
import org.gitlab4j.api.models.Project;

import edu.hm.hafner.grading.AggregatedScore;
import edu.hm.hafner.grading.AutoGradingRunner;
import edu.hm.hafner.grading.GradingReport;
import edu.hm.hafner.grading.QualityGateResult;
import edu.hm.hafner.util.FilteredLog;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * GitLab action entrypoint for the autograding action.
 *
 * @author Ullrich Hafner
 * @author Jannik Ohme
 */
public class GitLabAutoGradingRunner extends AutoGradingRunner {
    static final String AUTOGRADING_MARKER = "<!-- -[autograding-gitlab-action]- -->";

    /**
     * The public entry point for the action in the docker container simply calls the autograding runner.
     */
    void main() {
        new GitLabAutoGradingRunner().run();
    }

    @Override
    protected String getDisplayName() {
        return "GitLab Autograding";
    }

    @Override
    protected void publishGradingResult(final AggregatedScore score, final QualityGateResult qualityGateResult,
            final FilteredLog log) {
        var env = new Environment(log);
        var gitlabUrl = env.getString("CI_SERVER_URL");
        if (StringUtils.isBlank(gitlabUrl)) {
            log.logError("No CI_SERVER_URL defined - skipping");

            return;
        }
        var oAuthToken = env.getString("GITLAB_TOKEN");
        if (oAuthToken.isBlank()) {
            log.logError("No valid GITLAB_TOKEN found - skipping");

            return;
        }

        try (var gitLabApi = new GitLabApi(gitlabUrl, oAuthToken)) {
            gitLabApi.setRequestTimeout(5000, 10_000);
            gitLabApi.enableRequestResponseLogging(Level.FINE, 4_096);

            var projectId = env.getString("CI_PROJECT_ID");
            if (projectId.isBlank() || !StringUtils.isNumeric(projectId)) {
                log.logError("No valid CI_PROJECT_ID found - skipping");

                return;
            }

            var sha = env.getString("CI_COMMIT_SHA");
            if (sha.isBlank()) {
                log.logError("No valid CI_COMMIT_SHA found - skipping");

                return;
            }

            var project = gitLabApi.getProjectApi().getProject(Long.parseLong(projectId));

            grade(score, qualityGateResult, gitLabApi, project, sha, env, log);
        }
        catch (GitLabApiException exception) {
            throw new IllegalStateException("Error while accessing GitLab API", exception);
        }
    }

    @Override
    protected boolean failOnQualityGate() {
        return true;
    }

    private void grade(final AggregatedScore score, final QualityGateResult qualityGateResult, final GitLabApi gitLabApi, final Project project, final String sha,
            final Environment env, final FilteredLog log) throws GitLabApiException {
        var errors = createErrorMessageMarkdown(log);
        var qualityGateDetails = qualityGateResult.createMarkdownSummary();
        var report = new GradingReport();
        var comment = env.getBoolean("SKIP_DETAILS")
                ? report.getMarkdownSummary(score, getTitleName()) + errors + qualityGateDetails
                : report.getMarkdownDetails(score, getTitleName()) + errors + qualityGateDetails;
        comment = AUTOGRADING_MARKER + "\n\n" + comment + "\n\n<hr />\n\nCreated by " + getAutogradingVersionLink(log);
        var mergeRequestEnvironment = env.getString("CI_MERGE_REQUEST_IID");
        if (mergeRequestEnvironment.isBlank() || !StringUtils.isNumeric(mergeRequestEnvironment)) {
            if (showCommentsInCommit(log)) {
                commentCommit(score, gitLabApi, project, sha, env, log, comment);
            }
            else {
                log.logInfo("Skipping comments on single commit");
            }
        }
        else {
            commentMergeRequest(score, gitLabApi, project, sha, env, log, mergeRequestEnvironment, comment);
        }
        log.logInfo("GitLab Action has finished");
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void commentMergeRequest(final AggregatedScore score, final GitLabApi gitLabApi, final Project project,
            final String sha, final Environment env, final FilteredLog log, final String mergeRequestEnvironment,
            final String comment) throws GitLabApiException {
        var mergeRequestId = Long.parseLong(mergeRequestEnvironment);

        deleteExistingComments(gitLabApi, project, mergeRequestId, log);

        var versions = gitLabApi.getMergeRequestApi()
                .getDiffVersions(project.getId(), mergeRequestId);
        if (versions.isEmpty()) {
            log.logInfo("Diff versions are empty, adding line comments to commit");
            createLineCommentsOnCommit(gitLabApi, project, sha, score, env, log);
        }
        else {
            log.logInfo("Diff versions found, adding line comments to merge request diff");
            try {
                var mergeRequest = getMergeRequest(gitLabApi, project, mergeRequestId);
                createLineCommentsOnDiff(gitLabApi.getCommitsApi(), gitLabApi.getDiscussionsApi(), mergeRequest,
                        versions.getFirst(), score, env, log);
            }
            catch (GitLabApiException exception) {
                log.logException(exception, "While commenting on merge request !%d diff, an error occurred. "
                        + "Retrying to comment directly on the commit", mergeRequestId);
                createLineCommentsOnCommit(gitLabApi, project, sha, score, env, log);
            }
        }

        createCommentOnMergeRequest(gitLabApi, project, mergeRequestEnvironment, comment, log);
    }

    private MergeRequest getMergeRequest(final GitLabApi gitLabApi, final Project project, final long mergeRequestId)
            throws GitLabApiException {
        var api = gitLabApi.getMergeRequestApi();
        try {
            return api.getMergeRequest(project.getId(), mergeRequestId);
        }
        catch (GitLabApiException _) {
            return api.getMergeRequest(project.getId(), mergeRequestId); // try again
        }
    }

    private void commentCommit(final AggregatedScore score, final GitLabApi gitLabApi, final Project project,
            final String sha, final Environment env, final FilteredLog log, final String comment)
            throws GitLabApiException {
        createLineCommentsOnCommit(gitLabApi, project, sha, score, env, log);
        createCommentOnCommit(gitLabApi, project, sha, comment);
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

    private void createLineCommentsOnDiff(final CommitsApi commitsApi, final DiscussionsApi discussionsApi,
            final MergeRequest mergeRequest, final MergeRequestVersion lastVersion,
            final AggregatedScore score, final Environment env, final FilteredLog log) {
        if (canCreateLineComments(env)) {
            var annotationBuilder = new GitLabDiffCommentBuilder(commitsApi, discussionsApi, mergeRequest, lastVersion,
                    getWorkingDirectory(env), log);
            annotationBuilder.createAnnotations(score);
        }
        else {
            log.logInfo("Skipping line comments on merge request diff");
        }
    }

    private void createLineCommentsOnCommit(final GitLabApi gitLabApi, final Project project, final String sha,
            final AggregatedScore score, final Environment env, final FilteredLog log) {
        if (canCreateLineComments(env)) {
            var commentBuilder = new GitLabCommitCommentBuilder(gitLabApi.getCommitsApi(), project.getId(), sha,
                    getWorkingDirectory(env), log);
            commentBuilder.createAnnotations(score);
        }
        else {
            log.logInfo("Skipping line comments on commit");
        }
    }

    private String getWorkingDirectory(final Environment env) {
        return env.getString("CI_PROJECT_DIR") + "/";
    }

    private boolean canCreateLineComments(final Environment env) {
        return !env.getBoolean("SKIP_LINE_COMMENTS");
    }

    private void deleteExistingComments(final GitLabApi gitLabApi, final Project project,
            final long mergeRequestId, final FilteredLog log) throws GitLabApiException {
        var projectId = project.getId();

        log.logInfo("Deleting old auto-grading merge request summary notes");
        gitLabApi.getNotesApi()
                .getMergeRequestNotes(projectId, mergeRequestId).stream()
                .filter(note -> note.getBody().startsWith(AUTOGRADING_MARKER))
                .forEach(note -> delete(gitLabApi, note, projectId, mergeRequestId));
        log.logInfo("Deleting old auto-grading merge request annotation notes");
        gitLabApi.getDiscussionsApi()
                .getMergeRequestDiscussions(projectId, mergeRequestId).stream()
                .map(Discussion::getNotes).flatMap(Collection::stream)
                .filter(note -> note.getBody().startsWith(AUTOGRADING_MARKER))
                .forEach(note -> delete(gitLabApi, note, projectId, mergeRequestId));
    }

    private void createCommentOnMergeRequest(final GitLabApi gitLabApi, final Project project, final String mergeRequestId,
            final String comment, final FilteredLog log) throws GitLabApiException {
        var projectId = project.getId();
        var mergeRequestIid = Long.parseLong(mergeRequestId);

        log.logInfo("Creating merge request note");
        gitLabApi.getNotesApi().createMergeRequestNote(projectId, mergeRequestIid, comment, null, false);
    }

    private void delete(final GitLabApi gitLabApi, final Note note,
            final Long projectId, final long mergeRequestIid) {
        try {
            gitLabApi.getNotesApi().deleteMergeRequestNote(projectId, mergeRequestIid, note.getId());
        }
        catch (GitLabApiException _) {
            // ignore exceptions
        }
    }

    private void createCommentOnCommit(final GitLabApi gitLabApi, final Project project, final String sha, final String comment)
            throws GitLabApiException {
        gitLabApi.getCommitsApi().addComment(project.getId(), sha, comment);
    }

    private boolean showCommentsInCommit(final FilteredLog log) {
        var name = "SKIP_COMMIT_COMMENTS";
        var defined = StringUtils.isNotBlank(System.getenv(name));
        log.logInfo(">>>> %s: %b", name, defined);
        return !defined;
    }

    @Override
    protected Map<String, Set<Integer>> getModifiedLines(final FilteredLog log) {
        var env = new Environment(log);
        var gitlabUrl = env.getString("CI_SERVER_URL");
        var oAuthToken = env.getString("GITLAB_TOKEN");
        var projectId = env.getString("CI_PROJECT_ID");
        var mergeRequest = env.getString("CI_MERGE_REQUEST_IID");

        if (gitlabUrl.isBlank() || oAuthToken.isBlank() || projectId.isBlank() || !mergeRequest.isBlank()) {
            return Map.of();
        }

        long mergeRequestId;
        try {
            mergeRequestId = Long.parseLong(mergeRequest);
        }
        catch (NumberFormatException e) {
            log.logException(e, "No valid merge request ID found");
            return Map.of();
        }

        try (GitLabApi gitLabApi = new GitLabApi(gitlabUrl, oAuthToken)) {
            gitLabApi.setRequestTimeout(5000, 10_000);
            gitLabApi.enableRequestResponseLogging(Level.FINE, 4_096);

            var diffs = gitLabApi.getMergeRequestApi().getDiffs(projectId, mergeRequestId);
            return new DiffParser().getModifiedLines(diffs);
        }
        catch (GitLabApiException e) {
            log.logException(e, "Error while accessing GitLab API");
        }

        return Map.of();
    }
}
