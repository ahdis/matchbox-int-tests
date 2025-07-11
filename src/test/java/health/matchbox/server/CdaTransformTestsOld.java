package health.matchbox.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.api.EncodingEnum;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles("cda-transform")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("The Swiss CDA maps are not tested anymore")
public class CdaTransformTestsOld {

	private final FhirContext contextR4 = FhirContext.forR4Cached();
	private final GenericFhirClient genericClient = new GenericFhirClient(contextR4, "http://localhost:8083/matchboxv3/fhir");

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CdaTransformTestsOld.class);

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Path dir = Paths.get("database");
		if (Files.exists(dir)) {
			for (Path file : Files.list(dir).collect(Collectors.toList())) {
				if (Files.isRegularFile(file)) {
					Files.delete(file);
				}
			}
		}
		Thread.sleep(20000); // give the server some time to start up
		genericClient.capabilities();
	}

	@Test
	public void convertCdaToBundle() {
		String cda = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><typeId root=\"2.16.840.1.113883.1.3\" extension=\"POCD_HD000040\"/>\n" +
			"    <templateId root=\"2.16.756.5.30.1.127.1.4\"/></ClinicalDocument>";
		IBaseResource bundle = genericClient.convert(cda,
																	EncodingEnum.XML,
																	"http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaToBundle",
																	"application/json+fhir");
		assertEquals("Bundle", bundle.fhirType());
	}

	@Test
	public void convertBundleToCda() {
		String uuid = "urn:uuid:" + UUID.randomUUID();
		Bundle bundle = new Bundle();
		bundle.setIdentifier(new Identifier().setSystem("urn:ietf:rfc:3986").setValue(uuid));

		String cda = genericClient.convert(bundle,
													  EncodingEnum.JSON,
													  "http://fhir.ch/ig/cda-fhir-maps/StructureMap/BundleToCda",
													  "text/xml");
		assertNotNull(cda);
		assertTrue(cda.indexOf("ClinicalDocument") > 0);
	}

	private Bundle convertBundleToCdaAndBack(Bundle bundle, String mapToCda, String mapToBundle) {
		String cda = genericClient.convert(bundle, EncodingEnum.JSON, mapToCda, "text/xml");
		assertNotNull(cda);
		Bundle bundleReceived = (Bundle) genericClient.convert(cda,
																				 EncodingEnum.XML,
																				 mapToBundle,
																				 "application/json+fhir");
		assertNotNull(bundleReceived);
		return bundleReceived;
	}

	public void convertBundleMedicationTreatmentPlanToCdaAndBack(String url) throws IOException {
		InputStream inputStream = new URL(url).openStream();
		Bundle bundle = (Bundle) contextR4.newXmlParser().parseResource(inputStream);
		assertNotNull(bundle);
		Bundle bundleReceived = convertBundleToCdaAndBack(bundle,
																		  "http://fhir.ch/ig/cda-fhir-maps/StructureMap/BundleToCdaChEmedMedicationTreatmentPlanDocument",
																		  "http://fhir.ch/ig/cda-fhir-maps/StructureMap/CdaChEmedMedicationTreatmentPlanDocumentToBundle");
		CompareUtil.compare(bundle, bundleReceived, false);
	}

	@Test
	public void convert11MedicationTreatmentPlanToCdaAndBack() throws IOException {
		convertBundleMedicationTreatmentPlanToCdaAndBack(
			"http://fhir.ch/ig/ch-emed/2.1.0/Bundle-1-1-MedicationTreatmentPlan.xml");
	}

	public Map<String, String> harmonizeBunldeIds(Bundle bundle) {
		Map<String, String> ids = new HashMap<String, String>();
		int index = 0;
		for (BundleEntryComponent entry : bundle.getEntry()) {
			if (entry.getFullUrl() != null) {
				String resourceId = "res:" + index++;
				ids.put(entry.getFullUrl(), resourceId);
				if (entry.getFullUrl().startsWith("http")) {
					int slashResource = entry.getFullUrl().lastIndexOf("/");
					if (slashResource > 0) {
						int relResource = entry.getFullUrl().lastIndexOf("/", slashResource - 1);
						String relUrl = entry.getFullUrl().substring(relResource + 1);
						ids.put(relUrl, resourceId);
					}
				}
			}
		}
		return ids;
	}
}
