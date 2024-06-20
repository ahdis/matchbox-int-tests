package health.matchbox.server;

import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import ca.uhn.fhir.jpa.starter.Application;

/**
 * see https://www.baeldung.com/springjunit4classrunner-parameterized read the
 * implementation guides defined in ig and
 * execute the validations
 * <p>
 * It uses the port 8082.
 *
 * @author oliveregger
 **/
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = { Application.class })
@ActiveProfiles("validate-r4-ch-elm")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class IgValidateR4ChElmTest extends IgValidateR4 {

	private static final Logger log = LoggerFactory.getLogger(IgValidateR4ChElmTest.class);

	@Override
	protected String determineProfileToValidate(String name, Resource resource) {
		String profile = null;
		if (name.startsWith("ch.fhir.ig.ch-elm")) {
			if (resource.getResourceType() == org.hl7.fhir.r4.model.ResourceType.Bundle) {
				profile = "http://fhir.ch/ig/ch-elm/StructureDefinition/ch-elm-document-strict";
			}
			if (resource.getResourceType() == org.hl7.fhir.r4.model.ResourceType.DocumentReference) {
				profile = "http://fhir.ch/ig/ch-elm/StructureDefinition/PublishDocumentReferenceStrict";
			}
			if (profile == null) {
				Assumptions.abort("Ignoring validation for " + name + " since no profile found");
			}
		} else {
			profile = resource.getMeta().getProfile().get(0).getValue();
		}
		return profile;
	}

	@Override
	protected boolean exemptFile(String fn, String ig) {
		if (super.exemptFile(fn, ig)) {
			return true;
		}
		if (!(fn.startsWith("Bundle") || fn.startsWith("DocumentReference") || fn.startsWith("TestScript"))) {
			return true;
		}
		if (fn.startsWith("Bundle-ex-findDocumentReferencesResponse")) {
			return true;
		}
		if (fn.startsWith("DocumentReference-1-DocumentReferenceResponseFailed")) {
			return true;
		}
		if (fn.startsWith("DocumentReference-1-DocumentReferenceResponseCompleted")) {
			return true;
		}
		if (fn.startsWith("DocumentReference-1-DocumentReferenceResponseInProgress")) {
			return true;
		}
		return false;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("provideResources")
	public void testValidate(String name, Resource resource) throws Exception {
		super.testValidate(name, resource);
	}


}
