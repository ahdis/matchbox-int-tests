package health.matchbox.server;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.jpa.starter.Application;
import org.apache.commons.io.FileUtils;
import org.hl7.fhir.instance.model.api.*;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * matchbox-int-tests
 *
 * @author Quentin Ligier
 **/
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles("validate-r4-auto-install")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class IgValidateR4AutoInstallTests {
	private static final Logger log = LoggerFactory.getLogger(IgValidateR4AutoInstallTests.class);

	private final String targetServer = "http://localhost:8086/matchboxv3/fhir";
	final FhirContext contextR4 = FhirContext.forR4Cached();
	final ValidationClient validationClient = new ValidationClient(this.contextR4, this.targetServer);

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(30000); // give the server some time to start up
		this.validationClient.capabilities();
	}

	/**
	 * In this test, we validate an IPS resource and specify the IG and its version.
	 */
	@Test
	void validateIps() throws Exception {
		final String bundle = this.getContent("ips-minimal-err-patnobirthdate.json");

		IBaseOperationOutcome operationOutcome = this.validationClient.validate(
			bundle,
			"http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips",
			"hl7.fhir.uv.ips#1.1.0"
		);
		final String sessionId = this.getSessionId(operationOutcome);
		assertTrue(IgValidateR4.getValidationFailures((OperationOutcome) operationOutcome) > 1);
		assertEquals("hl7.fhir.uv.ips#1.1.0", this.getIg(operationOutcome));

		operationOutcome = this.validationClient.validate(
			bundle,
			"http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips",
			"hl7.fhir.uv.ips#1.1.0"
		);
		assertEquals(sessionId, this.getSessionId(operationOutcome));
		assertTrue(IgValidateR4.getValidationFailures((OperationOutcome) operationOutcome) > 1);
	}

	/**
	 * In this test, we validate a BALP resource without specifying the IG nor the profile version. We expect the last
	 * BALP version to be used.
	 */
	@Test
	void validateBalp() throws Exception {
		final String auditEvent = this.getContent("AuditEvent-ex-auditPrivacyDisclosure-recipient.json");

		IBaseOperationOutcome operationOutcome = this.validationClient.validate(
			auditEvent,
			"https://profiles.ihe.net/ITI/BALP/StructureDefinition/IHE.BasicAudit.PrivacyDisclosure.Recipient"
		);
		final String sessionId = this.getSessionId(operationOutcome);
		assertEquals(0, IgValidateR4.getValidationFailures((OperationOutcome) operationOutcome));
		assertEquals("ihe.iti.balp#1.1.3", this.getIg(operationOutcome));

		operationOutcome = this.validationClient.validate(
			auditEvent,
			"https://profiles.ihe.net/ITI/BALP/StructureDefinition/IHE.BasicAudit.PrivacyDisclosure.Recipient"
		);
		assertEquals(sessionId, this.getSessionId(operationOutcome));
		assertEquals(0, IgValidateR4.getValidationFailures((OperationOutcome) operationOutcome));
	}

	/**
	 * In this test, we validate a CH-Core resource with a profile version, but without specifying the IG. We expect the
	 * right IG version to be used.
	 */
	@Test
	void validateChCore() throws Exception {
		final String patient = this.getContent("Patient-UpiEprTestKrcmarevic.json");

		IBaseOperationOutcome operationOutcome = this.validationClient.validate(
			patient,
			"http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|4.0.1"
		);
		final String sessionId = this.getSessionId(operationOutcome);
		assertEquals(0, IgValidateR4.getValidationFailures((OperationOutcome) operationOutcome));
		assertEquals("ch.fhir.ig.ch-core#4.0.1", this.getIg(operationOutcome));

		operationOutcome = this.validationClient.validate(
			patient,
			"http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-patient|4.0.1"
		);
		assertEquals(sessionId, this.getSessionId(operationOutcome));
		assertEquals(0, IgValidateR4.getValidationFailures((OperationOutcome) operationOutcome));
	}

	private String getContent(String resourceName) throws IOException {
		Resource resource = new ClassPathResource("server/" + resourceName);
		File file = resource.getFile();
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
	}

	private String getSessionId(IBaseOperationOutcome outcome) {
		IBaseExtension<?, ?> ext = this.getMatchboxValidationExtension(outcome);
		if (ext == null) {
			log.error("Can't find the sessionId in the OperationOutcome:");
			log.error(this.contextR4.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		}
		List<IBaseExtension<?, ?>> extensions = (List<IBaseExtension<?, ?>>) ext.getExtension();
		for (IBaseExtension<?, ?> next : extensions) {
			if (next.getUrl().equals("sessionId")) {
				IPrimitiveType<?> value = (IPrimitiveType<?>) next.getValue();
				return value.getValueAsString();
			}
		}
		return null;
	}

	private String getIg(IBaseOperationOutcome outcome) {
		IBaseExtension<?, ?> ext = this.getMatchboxValidationExtension(outcome);
		if (ext == null) {
			log.error("Can't find the ig in the OperationOutcome:");
			log.error(this.contextR4.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome));
		}
		List<IBaseExtension<?, ?>> extensions = (List<IBaseExtension<?, ?>>) ext.getExtension();
		for (IBaseExtension<?, ?> next : extensions) {
			if (next.getUrl().equals("ig")) {
				IPrimitiveType<?> value = (IPrimitiveType<?>) next.getValue();
				return value.getValueAsString();
			}
		}
		return null;
	}

	private IBaseExtension<?, ?> getMatchboxValidationExtension(IBaseOperationOutcome theOutcome) {
		if (theOutcome == null) {
			return null;
		}
		RuntimeResourceDefinition ooDef = this.contextR4.getResourceDefinition(theOutcome);
		BaseRuntimeChildDefinition issueChild = ooDef.getChildByName("issue");
		List<IBase> issues = issueChild.getAccessor().getValues(theOutcome);
		if (issues.isEmpty()) {
			return null;
		}
		IBase issue = issues.getFirst();
		if (issue instanceof IBaseHasExtensions) {
			List<? extends IBaseExtension<?, ?>> extensions = ((IBaseHasExtensions) issue).getExtension();
			for (IBaseExtension<?, ?> nextSource : extensions) {
				if (nextSource.getUrl().equals("http://matchbox.health/validation")) {
					return nextSource;
				}
			}
		}
		return null;
	}
}
