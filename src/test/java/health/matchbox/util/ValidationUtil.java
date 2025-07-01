package health.matchbox.util;

import jakarta.annotation.Nullable;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.opentest4j.AssertionFailedError;

import java.util.stream.Collectors;

public class ValidationUtil {

	public static void assertNoValidationFailure(final OperationOutcome operationOutcome) {
		final var issues = operationOutcome.getIssue().parallelStream()
			.filter(issue -> OperationOutcome.IssueSeverity.FATAL == issue.getSeverity() || OperationOutcome.IssueSeverity.ERROR == issue.getSeverity())
			.toList();
		if (!issues.isEmpty()) {
			for (final var issue : issues) {
				System.err.println("Validation failure: " + toString(issue));
			}
			throw new AssertionFailedError(
				"Validation failed with %d errors. Expected no validation errors, but found %d: %s".formatted(
					issues.size(),
					issues.size(),
					issues.parallelStream().map(ValidationUtil::toString).collect(Collectors.joining(" | ")))
			);
		}
	}

	static public int getValidationFailures(final OperationOutcome outcome) {
		int fails = 0;
		if (outcome != null && outcome.getIssue() != null) {
			for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
				if (OperationOutcome.IssueSeverity.FATAL == issue.getSeverity() || OperationOutcome.IssueSeverity.ERROR == issue.getSeverity()) {
					++fails;
				}
			}
		}
		return fails;
	}

	public static String toString(final OperationOutcome.OperationOutcomeIssueComponent issue) {
		return "[%s][%s] %s %s".formatted(issue.getSeverity(),
													 issue.getCode(),
													 stringOrEmpty(issue.getDiagnostics()),
													 stringOrEmpty(issue.getDetails().getText()));
	}

	public static String stringOrEmpty(final @Nullable String string) {
		return (string == null || string.isBlank()) ? "" : string;
	}
}
