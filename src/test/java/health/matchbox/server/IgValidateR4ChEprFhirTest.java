package health.matchbox.server;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.hl7.fhir.r4.model.Resource;
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
@ActiveProfiles("validate-r4-ch-epr-fhir")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class IgValidateR4ChEprFhirTest extends IgValidateR4 {

	private static final Logger log = LoggerFactory.getLogger(IgValidateR4ChEprFhirTest.class);

	@Override
	protected boolean exemptFile(String fn, String ig) {
		if (super.exemptFile(fn, ig)) {
			return true;
		}

		if (fn.startsWith("AuditEvent-ex-auditProvideBundle-source")) {
			// https://github.com/ehealthsuisse/ch-epr-fhir/issues/153
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
