package io.quarkiverse.langchain4j.testing.scorer;

import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

class EvaluationReportTest {

    @Test
    void globalScoreShouldBeCorrect() {
        // Create mock evaluations.
        Scorer.EvaluationResult<String> result1 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample1", new Parameters(), "expected", List.of("tag1")),
                true);

        Scorer.EvaluationResult<String> result2 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample2", new Parameters(), "expected", List.of("tag2")),
                false);

        EvaluationReport report = new EvaluationReport(List.of(result1, result2));

        // Assertions
        assertThat(report.score()).isEqualTo(50.0); // 1 passed out of 2.
    }

    @Test
    void scoreForTagShouldBeCorrect() {
        // Create mock evaluations.
        Scorer.EvaluationResult<String> result1 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample1", new Parameters(), "expected", List.of("tag1")),
                true);

        Scorer.EvaluationResult<String> result2 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample2", new Parameters(), "expected", List.of("tag2")),
                false);

        Scorer.EvaluationResult<String> result3 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample3", new Parameters(), "expected", List.of("tag1", "tag2")),
                true);

        EvaluationReport report = new EvaluationReport(List.of(result1, result2, result3));

        // Assertions
        assertThat(report.scoreForTag("tag1")).isEqualTo(100.0); // Both tag1 samples passed.
        assertThat(report.scoreForTag("tag2")).isEqualTo(50.0); // 1 passed out of 2 for tag2.
    }

    @Test
    void writeReportShouldGenerateMarkdownFile() throws IOException {
        // Create mock evaluations.
        Scorer.EvaluationResult<String> result1 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample1", new Parameters(), "expected", List.of("tag1")),
                true);

        Scorer.EvaluationResult<String> result2 = new Scorer.EvaluationResult<>(
                new EvaluationSample<>("Sample2", new Parameters(), "expected", List.of("tag2")),
                false);

        EvaluationReport report = new EvaluationReport(List.of(result1, result2));

        // Write the report to a temporary file.
        File tempFile = File.createTempFile("evaluation-report", ".md");
        report.writeReport(tempFile);

        // Assertions
        assertThat(tempFile).exists();
        String content = Files.readString(tempFile.toPath());
        assertThat(content).contains("# Evaluation Report");
        assertThat(content).contains("**Global Score**: 50.0");
        assertThat(content).contains("## Score per tags");
        assertThat(content).contains("- **tag1**: 100.0");
        assertThat(content).contains("- **tag2**: 0.0");
        assertThat(content).contains("## Details");
        assertThat(content).contains("- Sample1: PASSED");
        assertThat(content).contains("- Sample2: FAILED");
    }
}
