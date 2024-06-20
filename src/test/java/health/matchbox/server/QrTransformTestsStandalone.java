package health.matchbox.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.rest.api.EncodingEnum;
import org.apache.commons.io.FileUtils;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled
public class QrTransformTestsStandalone {

	private final FhirContext contextR4 = FhirVersionEnum.R4.newContext();
	private final GenericFhirClient genericClient = new GenericFhirClient(contextR4,
                                                                          "http://10.2.254.194:8080/matchboxv3/fhir");

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CdaTransformTestsOld.class);

	@BeforeAll
	void waitUntilStartup() throws InterruptedException {
		Thread.sleep(20000); // give the server some time to start up
		genericClient.capabilities();
	}

	@Test
	public void converQrToBundle() throws IOException {
		String qr = getContent("qr.json");
		Bundle bundle = (Bundle) genericClient.convert(qr,
																	  EncodingEnum.JSON,
																	  "http://hcisolutions.ch/ig/ig-hci-vacd/StructureMap/HciVacQrToBundle",
																	  "application/fhir+json");
		assertNotNull(bundle);
		if (!bundle.getResourceType().equals(ResourceType.Bundle)) {
			log.error("wrong response " + bundle);
		}
        assertEquals(bundle.getResourceType(), ResourceType.Bundle);
	}

	private String getContent(String resourceName) throws IOException {
		ClassPathResource resource = new ClassPathResource(resourceName);
		File file = resource.getFile();
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
	}
}
