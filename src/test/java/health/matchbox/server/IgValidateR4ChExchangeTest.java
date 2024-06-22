package health.matchbox.server;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
@ActiveProfiles("validate-r4-ch-exchange")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
public class IgValidateR4ChExchangeTest extends IgValidateR4 {

	private static final Logger log = LoggerFactory.getLogger(IgValidateR4ChExchangeTest.class);

	@Override
	protected boolean exemptFile(String fn, String ig) {
		if (super.exemptFile(fn, ig)) {
			return true;
		}
		if (!(fn.startsWith("Bundle") || fn.startsWith("TestScript"))) {
			return true;
		}
		return false;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("provideResources")
	public void testValidate(String name, Resource resource) throws Exception {
		assumeFalse(name.equals("ch.fhir.ig.ch-ips-Bundle-UC1-SwissIpsDocument2"), "https://github.com/hl7ch/ch-ips/issues/4");
		if (!name.equals("ch.fhir.ig.ch-lab-report-Bundle-LabResultReport-2-electrophoresis")) {
			assumeFalse(name.startsWith("ch.fhir.ig.ch-lab-report-Bundle-"), "Performance issue see also https://github.com/ahdis/matchbox/issues/252");
		}
		super.testValidate(name, resource);
	}


}
