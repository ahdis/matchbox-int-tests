package health.matchbox.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.Application;
import health.matchbox.util.ValidationUtil;
import org.apache.commons.io.FileUtils;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Validation against hl7.fhir.eu.hdr#0.1.0-ballot.
 * <p>
 * The server validates with a copy of the main engine; the copy lost the package information and the master
 * definitions of the context, so the unversioned CodeSystem http://hl7.org/fhir/encounter-status referenced by the
 * ValueSet http://hl7.eu/fhir/hdr/ValueSet/encounter-status-eu-hdr was resolved to the R5 version (5.0.0, from
 * hl7.fhir.uv.xver-r5.r4) instead of the R4 one (4.0.1), and the code 'finished' was rejected.
 *
 * @see <a href="https://github.com/ahdis/matchbox/issues/538">Problem with multiple inheritance of the same valueset/terminology</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles("validate-r4-eu-hdr")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class IgValidateR4EuHdrTest {

	private final String targetServer = "http://localhost:8084/matchboxv3/fhir";
	private ValidationClient validationClient;
	private final FhirContext contextR4 = FhirContext.forR4Cached();

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(30000); // give the server some time to start up
		this.validationClient = new ValidationClient(this.contextR4, this.targetServer);
		this.validationClient.capabilities();
	}

	@Test
	// https://github.com/ahdis/matchbox/issues/538
	// Encounter.status 'finished' is in the R4 CodeSystem http://hl7.org/fhir/encounter-status (4.0.1), but not in
	// the R5 one (5.0.0). This validation should pass.
	void testIssue538EncounterStatusFinished() throws Exception {
		final var resource = this.getContent("eu_hdr_issue538_encounter.json");
		final var operationOutcome = (OperationOutcome) this.validationClient.validate(resource,
		                                                                               "http://hl7.eu/fhir/hdr/StructureDefinition/encounter-eu-hdr");
		ValidationUtil.assertNoValidationFailure(operationOutcome);
	}

	private String getContent(String resourceName) throws IOException {
		Resource resource = new ClassPathResource("server/" + resourceName);
		File file = resource.getFile();
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
	}
}
