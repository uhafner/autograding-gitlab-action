package edu.hm.hafner.grading.gitlab;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for the grading action. Starts the container and checks if the grading runs as expected.
 *
 * @author Ullrich Hafner
 */
public class GitLabAutoGradingRunnerDockerITest {
    private static final String CONFIGURATION = """
            {
              "tests": {
                "tools": [
                  {
                    "id": "junit",
                    "name": "Unittests",
                    "pattern": "**/target/*-reports/TEST*.xml"
                  }
                ],
                "name": "JUnit",
                "passedImpact": 10,
                "skippedImpact": -1,
                "failureImpact": -5,
                "maxScore": 100
              },
              "analysis": [
                {
                  "name": "Style",
                  "id": "style",
                  "tools": [
                    {
                      "id": "checkstyle",
                      "name": "CheckStyle",
                      "pattern": "**/checkstyle*.xml"
                    },
                    {
                      "id": "pmd",
                      "name": "PMD",
                      "pattern": "**/pmd*.xml"
                    }
                  ],
                  "errorImpact": 1,
                  "highImpact": 2,
                  "normalImpact": 3,
                  "lowImpact": 4,
                  "maxScore": 100
                },
                {
                  "name": "Bugs",
                  "id": "bugs",
                  "tools": [
                    {
                      "id": "spotbugs",
                      "name": "SpotBugs",
                      "pattern": "**/spotbugs*.xml"
                    }
                  ],
                  "errorImpact": -11,
                  "highImpact": -12,
                  "normalImpact": -13,
                  "lowImpact": -14,
                  "maxScore": 100
                }
              ],
              "coverage": [
              {
                  "tools": [
                      {
                        "id": "jacoco",
                        "metric": "line",
                        "pattern": "**/jacoco.xml"
                      },
                      {
                        "id": "jacoco",
                        "metric": "branch",
                        "pattern": "**/jacoco.xml"
                      }
                    ],
                "name": "JaCoCo",
                "maxScore": 100,
                "coveredPercentageImpact": 1,
                "missedPercentageImpact": -1
              },
              {
                  "tools": [
                      {
                        "id": "pit",
                        "name": "Mutation Coverage",
                        "metric": "mutation",
                        "pattern": "**/mutations.xml"
                      }
                    ],
                "name": "PIT",
                "maxScore": 100,
                "coveredPercentageImpact": 1,
                "missedPercentageImpact": -1
              } ]
            }
            """;
    private static final String WS = "/github/workspace/target/";

    @Test
    void shouldGradeInDockerContainer() throws TimeoutException {
        try (var container = createContainer()) {
            container.withEnv("CONFIG", CONFIGURATION);
            startContainerWithAllFiles(container);

            assertThat(readStandardOut(container))
                    .contains("Obtaining configuration from environment variable CONFIG")
                    .contains(new String[] {
                            "Processing 1 test configuration(s)",
                            "-> Unittests Total: TESTS: 1",
                            "JUnit Score: 10 of 100",
                            "Processing 2 coverage configuration(s)",
                            "-> Line Coverage Total: LINE: 10.93% (33/302)",
                            "-> Branch Coverage Total: BRANCH: 9.52% (4/42)",
                            "=> JaCoCo Score: 20 of 100",
                            "-> Mutation Coverage Total: MUTATION: 7.86% (11/140)",
                            "=> PIT Score: 16 of 100",
                            "Processing 2 static analysis configuration(s)",
                            "-> CheckStyle (checkstyle): 1 warning (normal: 1)",
                            "-> PMD (pmd): 1 warning (normal: 1)",
                            "=> Style Score: 6 of 100",
                            "-> SpotBugs (spotbugs): 1 bug (low: 1)",
                            "=> Bugs Score: 86 of 100",
                            "Autograding score - 138 of 500"});
        }
    }

    @Test
    void shouldUseDefaultConfiguration() throws TimeoutException {
        try (var container = createContainer()) {
            startContainerWithAllFiles(container);

            assertThat(readStandardOut(container))
                    .contains("No configuration provided (environment variable CONFIG not set), using default configuration")
                    .contains(new String[] {
                            "Processing 1 test configuration(s)",
                            "-> JUnit Tests Total: TESTS: 1",
                            "Tests Score: 100 of 100",
                            "Processing 2 coverage configuration(s)",
                            "-> Line Coverage Total: LINE: 10.93% (33/302)",
                            "-> Branch Coverage Total: BRANCH: 9.52% (4/42)",
                            "=> Code Coverage Score: 10 of 100",
                            "-> Mutation Coverage Total: MUTATION: 7.86% (11/140)",
                            "-> Test Strength Total: TEST_STRENGTH: 84.62% (11/13)",
                            "=> Mutation Coverage Score: 46 of 100",
                            "Processing 2 static analysis configuration(s)",
                            "-> CheckStyle (checkstyle): 1 warning (normal: 1)",
                            "-> PMD (pmd): 1 warning (normal: 1)",
                            "=> Style Score: 98 of 100",
                            "-> SpotBugs (spotbugs): 1 bug (low: 1)",
                            "=> Bugs Score: 97 of 100",
                            "Autograding score - 351 of 500 (70%)"});
        }
    }

    @Test
    void shouldShowErrors() throws TimeoutException {
        try (var container = createContainer()) {
            container.withWorkingDirectory("/github/workspace").start();
            assertThat(readStandardOut(container))
                    .contains(new String[] {
                            "Processing 1 test configuration(s)",
                            "=> Tests Score: 100 of 100",
                            "Configuration error for 'JUnit Tests'?",
                            "Processing 2 coverage configuration(s)",
                            "=> Code Coverage Score: 100 of 100",
                            "Configuration error for 'Line Coverage'?",
                            "Configuration error for 'Branch Coverage'?",
                            "=> Mutation Coverage Score: 100 of 100",
                            "Configuration error for 'Mutation Coverage'?",
                            "Processing 2 static analysis configuration(s)",
                            "Configuration error for 'CheckStyle'?",
                            "Configuration error for 'PMD'?",
                            "Configuration error for 'SpotBugs'?",
                            "-> CheckStyle (checkstyle): No warnings",
                            "-> PMD (pmd): No warnings",
                            "=> Style Score: 100 of 100",
                            "-> SpotBugs (spotbugs): No warnings",
                            "=> Bugs Score: 100 of 100",
                            "Autograding score - 500 of 500 (100%)"});
        }
    }

    private GenericContainer<?> createContainer() {
        return new GenericContainer<>(DockerImageName.parse("uhafner/autograding-gitlab-action:3.4.0-SNAPSHOT"));
    }

    private String readStandardOut(final GenericContainer<? extends GenericContainer<?>> container) throws TimeoutException {
        var waitingConsumer = new WaitingConsumer();
        var toStringConsumer = new ToStringConsumer();

        var composedConsumer = toStringConsumer.andThen(waitingConsumer);
        container.followOutput(composedConsumer);
        waitingConsumer.waitUntil(frame -> frame.getUtf8String().contains("End GitLab Autograding"), 60, TimeUnit.SECONDS);

        return toStringConsumer.toUtf8String();
    }

    private void startContainerWithAllFiles(final GenericContainer<?> container) {
        container.withWorkingDirectory("/github/workspace")
                .withCopyFileToContainer(read("checkstyle/checkstyle-result.xml"), WS + "checkstyle-result.xml")
                .withCopyFileToContainer(read("jacoco/jacoco.xml"), WS + "site/jacoco/jacoco.xml")
                .withCopyFileToContainer(read("junit/TEST-edu.hm.hafner.grading.AutoGradingActionTest.xml"), WS + "surefire-reports/TEST-Aufgabe3Test.xml")
                .withCopyFileToContainer(read("pit/mutations.xml"), WS + "pit-reports/mutations.xml")
                .withCopyFileToContainer(read("pmd/pmd.xml"), WS + "pmd.xml")
                .withCopyFileToContainer(read("spotbugs/spotbugsXml.xml"), WS + "spotbugsXml.xml")
                .start();
    }

    private MountableFile read(final String resourceName) {
        return MountableFile.forClasspathResource("/" + resourceName);
    }
}
