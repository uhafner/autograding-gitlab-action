package edu.hm.hafner.grading.gitlab;

import org.gitlab4j.api.models.Diff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class DiffParserTest {
    @Test
    void shouldReturnEmptyMapForEmptyDiffList() {
        List<Diff> diffs = List.of();
        var result = DiffParser.getModifiedLines(diffs);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnModifiedLinesForSingleFile() {
        Diff diff = new Diff();
        diff.setNewPath("FileName.java");
        diff.setDiff("""
                +++ FileName
                --- OldFileName
                @@ -1,3 +1,3 @@
                line1
                -line2
                +modifiedLine2
                line3
                """);

        var result = DiffParser.getModifiedLines(List.of(diff));
        assertThat(result).containsExactly(entry("FileName.java", Set.of(2)));
    }

    @Test
    void shouldHandleMultipleAddedLines() {
        var diff = new Diff();
        diff.setNewPath("FileName.java");
        diff.setDiff("""
                +++ FileName
                --- OldFileName
                @@ -0,0 +1,3 @@
                +line1
                +line2
                +line3
                """);

        var result = DiffParser.getModifiedLines(List.of(diff));
        assertThat(result).containsEntry("FileName.java", Set.of(1, 2, 3));
    }

    @Test
    void shouldHandleMultipleFiles() {
        var diff1 = new Diff();
        diff1.setNewPath("FileName1.java");
        diff1.setDiff("""
                +++ FileName
                --- OldFileName
                @@ -1,2 +1,2 @@
                line1
                +line2
                """);

        var diff2 = new Diff();
        diff2.setNewPath("FileName2.java");
        diff2.setDiff("""
                +++ FileName2
                --- OldFileName
                @@ -0,0 +1,1 @@
                +addedLine
                """);

        var result = DiffParser.getModifiedLines(List.of(diff1, diff2));
        assertThat(result)
                .hasSize(2)
                .containsEntry("FileName1.java", Set.of(2))
                .containsEntry("FileName2.java", Set.of(1));
    }
}
