package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.hm.hafner.analysis.IssueBuilder;
import edu.hm.hafner.analysis.Report;
import edu.hm.hafner.coverage.ModuleNode;
import edu.hm.hafner.coverage.Node;
import edu.hm.hafner.grading.AggregatedScore;
import edu.hm.hafner.grading.AnalysisConfiguration;
import edu.hm.hafner.grading.ToolConfiguration;
import edu.hm.hafner.grading.ToolParser;
import edu.hm.hafner.util.FilteredLog;

import static edu.hm.hafner.grading.gitlab.GitLabDiffCommentBuilder.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitLabDiffCommentBuilderTest {
    private static final String FILE_NAME = "src/main/java/File.java";
    private static final String URL = "https://gitlab.lrz.de/dev/java2-assignment1";
    private static final String SHA = "58c1e8a980dc0beb7d92d2266eb3e58852720a76";
    private static final String FILE = "src/main/java/edu/hm/hafner/java2/assignment1/Assignment.java";

    private static final String ANALYSIS_CONFIGURATION = """
            {
              "analysis": [
                {
                  "name": "Style",
                  "id": "style",
                  "tools": [
                    {
                      "id": "checkstyle",
                      "name": "Checkstyle",
                      "pattern": "checkstyle.xml"
                    }
                  ]
                }
              ]
            }
            """;
    private static final String PROJECT_URL = "CI_PROJECT_URL";
    private static final String COMMIT_SHA = "CI_COMMIT_SHA";

    @Test
    void shouldCreateRange() {
        var builder = spy(GitLabCommentBuilder.class);

        assertThat(builder.createRange('L', 0, 0)).isEmpty();
        assertThat(builder.createRange('L', -1, 10)).isEmpty();

        assertThat(builder.createRange('L', 1, 10)).isEqualTo("L1-L10");
        assertThat(builder.createRange('L', 1, 1)).isEqualTo("L1");
    }

    @Test
    void shouldCreateLinesAndColumns() {
        var builder = spy(GitLabCommentBuilder.class);

        assertThat(builder.createLinesAndColumns("L1", 0, 0)).isEqualTo("(L1)");
        assertThat(builder.createLinesAndColumns("L1", 1, 0)).isEqualTo("(L1:C1)");
        assertThat(builder.createLinesAndColumns("L1", 2, 3)).isEqualTo("(L1:C2-C3)");
    }

    @Test
    void shouldCreateMarkDownMessage() {
        var builder = spy(GitLabCommentBuilder.class);

        assertThat(builder.createMarkdownMessage(
                CommentType.WARNING, FILE,
                10, 20, 5, 8,
                "Title", "Message", "Details", this::getEnv))
                .contains("#### :warning: &nbsp; Title", "Message", "Details",
                        "[Assignment.java(L10-L20:C5-C8)]",
                        URL + "/blob/" + SHA + "/" + FILE + "#L10-L20");
        assertThat(builder.createMarkdownMessage(
                CommentType.WARNING, FILE,
                10, 20, 0, 8,
                "Title", "Message", "Details", this::getEnv))
                .contains("#### :warning: &nbsp; Title", "Message", "Details",
                        "[Assignment.java(L10-L20)]",
                        URL + "/blob/" + SHA + "/" + FILE + "#L10-L20");
        assertThat(builder.createMarkdownMessage(
                CommentType.WARNING, FILE,
                10, 10, 0, 8,
                "Title", "Message", "Details", this::getEnv))
                .contains("#### :warning: &nbsp; Title", "Message", "Details",
                        "[Assignment.java(L10)]",
                        URL + "/blob/" + SHA + "/" + FILE + "#L10");
    }

    private String getEnv(final String environment) {
        if (PROJECT_URL.equals(environment)) {
            return URL;
        }
        if (COMMIT_SHA.equals(environment)) {
            return SHA;
        }
        throw new IllegalArgumentException("Unknown environment: " + environment);
    }

    @Test
    void shouldCreateComment() throws GitLabApiException {
        var discussions = mock(DiscussionsApi.class);
        var commits = mock(CommitsApi.class);
        var builder = new GitLabDiffCommentBuilder(commits, discussions, mock(MergeRequest.class),
                mock(MergeRequestVersion.class), "/work", new FilteredLog("GitLab"));

        builder.createComment(CommentType.WARNING, FILE_NAME, 10, 100,
                "Message", "Title", 1, 10, "Details", "Details-Markdown");

        var details = ArgumentCaptor.forClass(String.class);
        verify(discussions).createMergeRequestDiscussion(anyLong(), anyLong(), details.capture(), isNull(), isNull(),
                any());

        assertThat(details.getValue()).contains(
                "#### :warning: &nbsp; Title",
                "[File.java(L10-L100:C1-C10)](/blob//src/main/java/File.java#L10-L100)",
                "Message",
                "Details");
    }

    @Test
    void shouldCreateAnnotation() throws GitLabApiException {
        var discussions = mock(DiscussionsApi.class);
        var commits = mock(CommitsApi.class);
        var gitlab = new GitLabDiffCommentBuilder(commits, discussions, mock(MergeRequest.class), mock(
                        MergeRequestVersion.class), "/work", new FilteredLog("GitLab"));

        var score = new AggregatedScore(new FilteredLog("Tests"));
        score.gradeAnalysis(new ReportGenerator(), AnalysisConfiguration.from(ANALYSIS_CONFIGURATION));

        gitlab.createAnnotations(score);

        var details = ArgumentCaptor.forClass(String.class);
        verify(discussions).createMergeRequestDiscussion(anyLong(), anyLong(), details.capture(), isNull(), isNull(),
                any());

        assertThat(details.getValue()).contains(
                "#### :warning: &nbsp; CheckStyle: HiddenField",
                        "[File.java(L10-L100:C1-C10)](/blob//src/main/java/File.java#L10-L100): Message",
                        "<p>Since Checkstyle 3.0</p><p>",
                        "Checks that a local variable or a parameter does not shadow a field",
                        "that is defined in the same class.");
    }

    private static class ReportGenerator implements ToolParser {
        @Override
        public Report readReport(final ToolConfiguration tool, final FilteredLog log) {
            return createReport();
        }

        private Report createReport() {
            var report = new Report();
            try (var builder = new IssueBuilder()) {
                report.add(builder.setFileName(FILE_NAME)
                        .setLineStart(10)
                        .setLineEnd(100)
                        .setOrigin("checkstyle")
                        .setOriginName("CheckStyle")
                        .setMessage("Message")
                        .setCategory("Title")
                        .setType("HiddenField")
                        .setColumnStart(1)
                        .setColumnEnd(10)
                        .build());
            }
            return report;
        }

        @Override
        public Node readNode(final ToolConfiguration configuration, final String directory, final FilteredLog log) {
            return new ModuleNode("module");
        }
    }
}
