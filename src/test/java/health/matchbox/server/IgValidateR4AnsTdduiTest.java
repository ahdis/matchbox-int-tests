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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles("validate-r4-ans-tddui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class IgValidateR4AnsTdduiTest {

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
		// This validation should pass
	void testIssue538() throws Exception {
		final var resource = this.getContent("ans_tddui_issue538.json");
		final var operationOutcome = (OperationOutcome) this.validationClient.validate(resource,
		                                                                               "https://interop.esante.gouv.fr/ig/fhir/tddui/StructureDefinition/tddui-basic-decision");
		ValidationUtil.assertNoValidationFailure(operationOutcome);
	}

	private String getContent(String resourceName) throws IOException {
		Resource resource = new ClassPathResource("server/" + resourceName);
		File file = resource.getFile();
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
	}
}
