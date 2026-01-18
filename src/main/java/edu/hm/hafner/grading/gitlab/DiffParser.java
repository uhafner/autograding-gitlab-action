package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.models.Diff;

import java.util.*;

public class DiffParser {
    public static Map<String, Set<Integer>> getModifiedLines(final List<Diff> diffs) {
        Map<String, Set<Integer>> changedLinesByFile = new HashMap<>();

        for (Diff diff : diffs) {
            Set<Integer> changedLines = new HashSet<>();

            var diffText = diff.getDiff();
            int lineNum = 0;
            for (var line : diffText.split("\n")) {
                if (line.startsWith("@@")) {
                    var parts = line.split(" ");
                    var newRange = parts[2];
                    var rangeParts = newRange.substring(1).split(",");
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