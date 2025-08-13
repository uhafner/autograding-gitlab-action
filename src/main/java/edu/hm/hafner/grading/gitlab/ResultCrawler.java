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
            = Pattern.compile("##.*?([\\p{L}\\s:]+)- (\\d+) of (\\d+)");
    private static final Pattern GITLAB_TOKEN
            = Pattern.compile("glpat-[A-Za-z0-9_\\-]+");

    /**
     * Starts the crawler. Usage: {@code ResultCrawler [assignment-name [merge-request-label]]}.
     *
     * @param args
     *         the command line arguments, where the first argument is the assignment name (optional).
     *         The second argument is the merge request label to filter by (optional).
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

                List<MergeRequest> mergeRequests = gitLabApi.getMergeRequestApi().getMergeRequests(project.getId());

                int mrIndex = 0;
                for (MergeRequest mr : mergeRequests) {
                    mrIndex++;
                    totalMergeRequests++;

                    if (isValidLabel(label) && !mr.getLabels().contains(label)) {
                        print("   - MR [%d/%d]: %s - Skipping MR since labels %s do not contain '%s'%n",
                                mrIndex, mergeRequests.size(), mr.getTitle(),
                                mr.getLabels(), label);
                        continue;
                    }

                    print("   + MR [%d/%d]: %s%n", mrIndex, mergeRequests.size(), mr.getTitle());

                    // Check if a successful pipeline exists for the MR SHA
                    Optional<Pipeline> pipelineOpt = gitLabApi.getPipelineApi()
                            .getPipelines(project.getId(), new PipelineFilter().withSha(mr.getSha()))
                            .stream().findFirst();

                    if (pipelineOpt.isEmpty() || pipelineOpt.get().getStatus() != PipelineStatus.SUCCESS) {
                        // No pipeline or failed pipeline
                        failedOrMissingPipelines++;
                        String[] row = new String[3 + categories.size()];
                        row[0] = studentName;
                        row[1] = mr.getTitle();
                        Arrays.fill(row, 2, row.length - 1, "-");
                        row[row.length - 1] = "Failed or missing pipeline";
                        rows.add(row);
                        continue;
                    }
                    else {
                        successfulPipelines++;
                    }

                    // Retrieve all comments (notes) on the merge request
                    List<Note> notes = gitLabApi.getNotesApi().getMergeRequestNotes(project.getId(), mr.getIid());

                    boolean found = false;
                    for (Note note : notes) {
                        // Only consider comments created by the autograding bot
                        if (!note.getAuthor().getName().equals("AUTOGRADING_BOT")) {
                            continue;
                        }
                        String body = note.getBody();
                        if (!body.startsWith("<!-- -[autograding-gitlab-action]- -->")) {
                            continue;
                        }
                        if (!body.contains("Autograding score")) {
                            continue;
                        }

                        usedAutogradingNotes++;
                        found = true;

                        Map<String, String> scores = new LinkedHashMap<>();
                        scores.put("Repo", studentName);
                        scores.put("MergeRequest", mr.getTitle());

                        // Extract categories and scores with pattern matching
                        // categories, because it then also works with different autograding configurations
                        Matcher blockMatcher = CATEGORIES_AND_SCORES.matcher(body);
                        while (blockMatcher.find()) {
                            String category = blockMatcher.group(1).trim().replaceAll("\\s+", " ");
                            String score = blockMatcher.group(2);
                            String total = blockMatcher.group(3);
                            String percent = String.format("%.0f%%",
                                    (Double.parseDouble(score) / Double.parseDouble(total)) * 100);
                            scores.put(category, percent);
                            if (!categories.contains(category)) {
                                categories.add(category);
                            }
                        }

                        String[] row = new String[3 + categories.size()];
                        row[0] = scores.get("Repo");
                        row[1] = scores.get("MergeRequest");
                        for (int i = 0; i < categories.size(); i++) {
                            row[i + 2] = scores.getOrDefault(categories.get(i), "-");
                        }
                        row[row.length - 1] = "OK"; // Success
                        rows.add(row);
                        break; // Only evaluate one autograding comment per MR
                    }

                    if (!found) {
                        // Pipeline succeeded but no autograding comment found
                        noAutogradingNotes++;
                        String[] row = new String[3 + categories.size()];
                        row[0] = studentName;
                        row[1] = mr.getTitle();
                        Arrays.fill(row, 2, row.length - 1, "-");
                        row[row.length - 1] = "No autograding comments";
                        rows.add(row);
                    }
                }
            }
        }

        // Write the results to a CSV file
        writeCsvFile(categories, rows);

        System.out.println();
        System.out.println("Done. Results written to autograding-results.csv.");
        System.out.println("--------- Summary ---------");
        System.out.println("Total projects:                   " + totalProjects);
        System.out.println("Total merge requests:            " + totalMergeRequests);
        System.out.println("Successful pipelines:            " + successfulPipelines);
        System.out.println("Failed or missing pipelines:     " + failedOrMissingPipelines);
        System.out.println("Used autograding comments:       " + usedAutogradingNotes);
        System.out.println("No autograding comments in MR:   " + noAutogradingNotes);
    }

    private boolean isValidLabel(final String label) {
        return StringUtils.isNotBlank(label) && !label.equals("-");
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
