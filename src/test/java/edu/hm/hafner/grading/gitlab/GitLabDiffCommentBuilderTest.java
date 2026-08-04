package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.DiscussionsApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestVersion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.hm.hafner.util.FilteredLog;

import java.util.Map;

import static edu.hm.hafner.grading.gitlab.GitLabDiffCommentBuilder.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitLabDiffCommentBuilderTest {
    private static final String FILE_NAME = "src/main/java/File.java";
    private static final String URL = "https://gitlab.lrz.de/dev/java2-assignment1";
    private static final String SHA = "58c1e8a980dc0beb7d92d2266eb3e58852720a76";
    private static final String FILE = "src/main/java/edu/hm/hafner/java2/assignment1/Assignment.java";

    private static final String PROJECT_URL = "CI_PROJECT_URL";
    private static final String COMMIT_SHA = "CI_COMMIT_SHA";

    @Test
    void shouldCreateRange() {
        assertThat(createRange('L', 0, 0)).isEmpty();
        assertThat(createRange('L', -1, 10)).isEmpty();

        assertThat(createRange('L', 1, 10)).isEqualTo("L1-L10");
        assertThat(createRange('L', 1, 1)).isEqualTo("L1");
    }

    @Test
    void shouldCreateLinesAndColumns() {
        assertThat(createLinesAndColumns("L1", 0, 0)).isEqualTo("(L1)");
        assertThat(createLinesAndColumns("L1", 1, 0)).isEqualTo("(L1:C1)");
        assertThat(createLinesAndColumns("L1", 2, 3)).isEqualTo("(L1:C2-C3)");
    }

    @Test
    void shouldCreateMarkDownMessage() {
        assertThat(createMarkdownMessage(
                CommentType.WARNING, FILE,
                10, 20, 5, 8,
                "Title", "Message", "Details", this::getEnv))
                .contains("#### :warning: &nbsp; Title", "Message", "Details",
                        "[Assignment.java(L10-L20:C5-C8)]",
                        URL + "/blob/" + SHA + "/" + FILE + "#L10-L20");
        assertThat(createMarkdownMessage(
                CommentType.WARNING, FILE,
                10, 20, 0, 8,
                "Title", "Message", "Details", this::getEnv))
                .contains("#### :warning: &nbsp; Title", "Message", "Details",
                        "[Assignment.java(L10-L20)]",
                        URL + "/blob/" + SHA + "/" + FILE + "#L10-L20");
        assertThat(createMarkdownMessage(
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
        var builder = new GitLabDiffCommentBuilder(commits, Map.of(), discussions, mock(MergeRequest.class),
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
        var gitlab = new GitLabDiffCommentBuilder(commits, Map.of(), discussions, mock(MergeRequest.class), mock(
                        MergeRequestVersion.class), "/work", new FilteredLog("GitLab"));

        gitlab.createComment(CommentType.WARNING, "src/main/java/File.java",
                10, 100, "Message", "CheckStyle: HiddenField", 1, 10,
                "", "<p>Since Checkstyle 3.0</p><p>");

        var details = ArgumentCaptor.forClass(String.class);
        verify(discussions).createMergeRequestDiscussion(anyLong(), anyLong(), details.capture(), isNull(), isNull(),
                any());

        assertThat(details.getValue()).contains(
                "#### :warning: &nbsp; CheckStyle: HiddenField",
                        "[File.java(L10-L100:C1-C10)](/blob//src/main/java/File.java#L10-L100): Message",
                        "<p>Since Checkstyle 3.0</p><p>");
    }
}
