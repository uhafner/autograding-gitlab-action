package edu.hm.hafner.grading.gitlab;

import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.Note;
import org.gitlab4j.api.models.Pipeline;
import org.gitlab4j.api.models.PipelineFilter;
import org.gitlab4j.api.models.PipelineStatus;
import org.gitlab4j.api.models.Project;

import com.google.errorprone.annotations.FormatMethod;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Crawls a GitLab group for projects and merge requests, retrieves autograding comments, and generates a CSV report.
 *
 * @author Stefan Drexler
 */
public class ResultCrawler {
    private static final String GITLAB_HOST_URL = "https://gitlab.lrz.de";

    // ------- Default values that can be changed as needed -------
    private static final String DEFAULT_GROUP_PATH = "dev/courses/java2/"; // the top-level group, for example, dev/courses/java2/
    private static final String DEFAULT_ASSIGNMENT = "assignment4"; // the assignment name, for example, assignment7
    private static final String DEFAULT_MR_LABEL = "solution"; // the label to filter merge requests, for example, solution

    private static final Set<String> SKIP_PROJECTS_FROM = Set.of("hafner"); // students to skip, for example, "hafner"
    // ------- No need to change anything below this line -------

    private static final Pattern GITLAB_TOKEN_PATTERN = Pattern.compile("glpat-[A-Za-z0-9_\\-]+");

    private static final Pattern CATEGORIES_AND_SCORES
            = Pattern.compile("##.*?(?<category>[\\p{L}\\s:]+)- (?<value>\\d+) of (?<total>\\d+)");

    private static final String EMPTY = "-"; // Placeholder for empty values in the CSV output

    // Column names for the CSV output
    private static final String URL = "URL";
    private static final String PIPELINE = "Pipeline";
    private static final String MR_NUMBER = "MR #";
    private static final String MR_NAME = "MR Name";

    /**
     * Starts the crawler. Usage: {@code ResultCrawler [assignment-name [merge-request-label]]}.
     *
     * @param args
     *         the command line arguments, where the first argument is the assignment name (optional). The second
     *         argument is the merge request label to filter by (optional).
     *
     * @throws GitLabApiException
     *         if there is an error accessing the GitLab API
     * @throws IOException
     *         if there is an error reading the GitLab token from the configuration file
     */
    public static void main(final String... args) throws GitLabApiException, IOException {
        var crawler = new ResultCrawler();

        var assignment = args.length == 1 ? args[0] : DEFAULT_ASSIGNMENT;
        var label = args.length == 2 ? args[1] : DEFAULT_MR_LABEL;

        crawler.createResultsFor(DEFAULT_GROUP_PATH + assignment, label);
    }

    private void createResultsFor(final String repositoryPath, final String label)
            throws GitLabApiException, IOException {
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();

        var token = readGitLabTokenFromGlabsConfiguration();
        try (GitLabApi gitLabApi = new GitLabApi(GITLAB_HOST_URL, token)) {
            var projects = readProjects(repositoryPath, gitLabApi);

            int projectIndex = 0;
            for (Project project : projects) {
                projectIndex++;

                var studentName = StringUtils.substringBetween(project.getName(), "-", "_at");
                if (SKIP_PROJECTS_FROM.contains(studentName)) {
                    continue;
                }

                print("→ [%d/%d] Student: %s%n", projectIndex, projects.size(), studentName);

                Map<String, String> scores = new LinkedHashMap<>();
                rows.put(studentName, scores);

                scores.put("Student", studentName);

                Optional<MergeRequest> mergeRequests = gitLabApi.getMergeRequestApi().getMergeRequests(project.getId())
                        .stream().filter(m -> m.getLabels().contains(label)).findFirst();
                if (mergeRequests.isEmpty()) {
                    scores.put(URL, project.getWebUrl() + "/-/merge_requests");
                    skip("no merge request contains label " + label, scores);
                    continue;
                }

                var mr = mergeRequests.get();

                scores.put(MR_NUMBER, String.valueOf(mr.getIid()));
                scores.put(MR_NAME, mr.getTitle());
                scores.put(URL, mr.getWebUrl());

                Optional<Pipeline> possiblePipeline = gitLabApi.getPipelineApi()
                        .getPipelines(project.getId(), new PipelineFilter().withSha(mr.getSha()))
                        .stream().findFirst();

                if (possiblePipeline.isEmpty()) {
                    skip("no pipeline found", scores);
                    scores.put(PIPELINE, "No pipeline found");
                    continue;
                }

                if (possiblePipeline.get().getStatus() != PipelineStatus.SUCCESS) {
                    skip("no successful pipeline found", scores);
                    scores.put(PIPELINE, "No successful pipeline found");
                    continue;
                }

                Optional<Note> notes = gitLabApi.getNotesApi().getMergeRequestNotes(project.getId(), mr.getIid())
                        .stream()
                        .filter(note -> note.getAuthor().getName().equals("AUTOGRADING_BOT"))
                        .filter(note -> note.getBody().startsWith("<!-- -[autograding-gitlab-action]- -->"))
                        .filter(note -> note.getBody().contains("Autograding score"))
                        .findFirst();

                if (notes.isEmpty()) {
                    skip("no Autograding comments found", scores);
                    scores.put(PIPELINE, "No autograding comments found");
                    continue;
                }
                scores.put(URL, mr.getWebUrl() + "#note_" + notes.get().getId());

                scores.put(PIPELINE, "Success");

                var gradingNote = notes.get();
                scores.putAll(readGradingComments(gradingNote));
            }
        }

        writeCsvFile(rows);
    }

    private void skip(final String reason, final Map<String, String> scores) {
        print("   ! Skipping project: %s - %s%n", reason, scores.getOrDefault(URL, EMPTY));
    }

    /**
     * Extract the available categories and scores with pattern matching so it will work with different autograding
     * configurations.
     *
     * @param gradingNote
     *         the note containing the grading comments
     *
     * @return a map of category names to scores as percentages
     */
    private Map<String, String> readGradingComments(final Note gradingNote) {
        var scores = new LinkedHashMap<String, String>();
        Matcher blockMatcher = CATEGORIES_AND_SCORES.matcher(gradingNote.getBody());
        while (blockMatcher.find()) {
            String category = blockMatcher.group("category").trim().replaceAll("\\s+", " ");
            String score = blockMatcher.group("value");
            String total = blockMatcher.group("total");
            String percent = String.format(Locale.ENGLISH, "%.0f%%",
                    Double.parseDouble(score) / Double.parseDouble(total) * 100);
            scores.put(category, percent);
        }
        return scores;
    }

    private List<Project> readProjects(final String repositoryPath, final GitLabApi gitLabApi)
            throws GitLabApiException {
        print("→ Obtaining projects from %s ... this will take some time%n", repositoryPath);
        List<Project> projects = gitLabApi.getGroupApi().getProjects(repositoryPath);
        print("→ Found %d projects%n", projects.size());
        projects.sort(Comparator.comparing(Project::getName));
        return projects;
    }

    private void writeCsvFile(final Map<String, Map<String, String>> rows) throws IOException {
        try (Writer writer = Files.newBufferedWriter(Path.of("autograding-results.csv"))) {
            var maxEntry = rows.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().size()))
                    .orElseThrow(() -> new IllegalStateException("No entries found in rows to determine categories"));

            var columns = maxEntry.getValue().keySet().stream().filter(key -> !URL.equals(key))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            columns.add(URL); // Ensure URL is always the last column

            writer.append(String.join(", ", columns));
            writer.append("\n");
            writer.append(rows.values().stream()
                    .map(results -> writeRow(columns, results))
                    .collect(Collectors.joining("\n")));
        }
    }

    private String writeRow(final Set<String> keys, final Map<String, String> results) {
        return keys.stream()
                .map(key -> results.getOrDefault(key, EMPTY))
                .collect(Collectors.joining(", "));
    }

    private String readGitLabTokenFromGlabsConfiguration() throws IOException {
        String home = System.getProperty("user.home");
        String content = Files.readString(Path.of(home, ".glabs.yml"));
        Matcher tokenMatcher = GITLAB_TOKEN_PATTERN.matcher(content);
        if (!tokenMatcher.find()) {
            throw new IllegalStateException("No GitLab token found in $HOME/.glabs.yml");
        }
        return tokenMatcher.group();
    }

    @FormatMethod
    @SuppressWarnings({"PMD.SystemPrintln", "SystemOut"})
    private void print(final String format, final Object... args) {
        System.out.printf(format, args);
    }
}
