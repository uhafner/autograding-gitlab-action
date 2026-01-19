package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.models.Diff;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides changed lines for a GitLab merge request so patch coverage can be computed.
 */
// TODO: extract common logic with GitHub DiffParser
class DiffParser {
    /**
     * Parses the given list of diffs and returns a map of changed lines by file.
     *
     * @param diffs
     *         the list of diffs
     *
     * @return a map of changed lines by file
     */
    Map<String, Set<Integer>> getModifiedLines(final List<Diff> diffs) {
        Map<String, Set<Integer>> changedLinesByFile = new HashMap<>();

        for (Diff diff : diffs) {
            Set<Integer> changedLines = new HashSet<>();

            var diffText = diff.getDiff();
            int lineNum = 0;
            for (var line : diffText.split("\n", 0)) {
                if (line.startsWith("@@")) {
                    var parts = line.split(" ", 0);
                    var newRange = parts[2];
                    var rangeParts = newRange.substring(1).split(",", 0);
                    try {
                        lineNum = Integer.parseInt(rangeParts[0]);
                    }
                    catch (NumberFormatException e) {
                        return Map.of();
                    }
                }
                else if (line.startsWith("+") && !line.startsWith("+++")) {
                    changedLines.add(lineNum);
                    lineNum++;
                }
                else if (!line.startsWith("-")) {
                    lineNum++;
                }
            }

            var fileName = diff.getNewPath();
            changedLinesByFile.put(fileName, changedLines);
        }
        return changedLinesByFile;
    }
}
