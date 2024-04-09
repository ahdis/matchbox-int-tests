package health.matchbox.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.packages.loader.PackageLoaderSvc;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.util.PackageCacheInitializer;
import health.matchbox.util.ValidationClient;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * see https://www.baeldung.com/springjunit4classrunner-parameterized read the implementation guides defined in ig and
 * execute the validations
 * <p>
 * It uses the port 8082.
 *
 * @author oliveregger
 **/
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class})
@ActiveProfiles("validate-r4")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IgValidateR4Test {

	private static final String TARGET_SERVER = "http://localhost:8082/matchboxv3/fhir";
	private static final Logger log = LoggerFactory.getLogger(IgValidateR4Test.class);
	@Autowired
	ApplicationContext context;
	private ValidationClient validationClient;

	private static final String EMED = "ch.fhir.ig.ch-emed";
	private static final String ELM = "ch.fhir.ig.ch-elm";

	static public int getValidationFailures(OperationOutcome outcome) {
		int fails = 0;
		if (outcome != null && outcome.getIssue() != null) {
			for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
				if (OperationOutcome.IssueSeverity.FATAL == issue.getSeverity()) {
					++fails;
				}
				if (OperationOutcome.IssueSeverity.ERROR == issue.getSeverity()) {
					++fails;
				}
			}
		}
		return fails;
	}

	@BeforeAll
	public synchronized void beforeAll() throws Exception {
		Thread.sleep(40000); // give the server some time to start up
		FhirContext contextR4 = FhirVersionEnum.R4.newContext();
		this.validationClient = new ValidationClient(contextR4, TARGET_SERVER);
		this.validationClient.capabilities();
	}

	public Stream<Arguments> provideResources() throws Exception {

		Map<String, Object> obj = new Yaml().load(getClass().getResourceAsStream("/application-validate-r4.yaml"));
		final List<AppProperties.ImplementationGuide> igs = PackageCacheInitializer.getIgs(obj, true);
		List<Arguments> arguments = new ArrayList<>();
		for (AppProperties.ImplementationGuide ig : igs) {
			Map<String, byte[]> source = fetchByPackage(ig, true);
			String version = "4.0.1";
			for (Map.Entry<String, byte[]> t : source.entrySet()) {
				String fn = t.getKey();
				if (!exemptFile(fn, ig.getName())) {
					Resource r = null;
					if (fn.endsWith(".xml") && !fn.endsWith("template.xml"))
						r = new org.hl7.fhir.r4.formats.XmlParser().parse(new ByteArrayInputStream(t.getValue()));
					else if (fn.endsWith(".json") && !fn.endsWith("template.json"))
						r = new org.hl7.fhir.r4.formats.JsonParser().parse(new ByteArrayInputStream(t.getValue()));
					else if (fn.endsWith(".txt") || fn.endsWith(".map"))
						r = new org.hl7.fhir.r4.utils.StructureMapUtilities(null).parse(new String(t.getValue()), fn);
					else
						throw new Exception("Unsupported format for " + fn);
					if (r != null) {
						arguments.add(Arguments.of(ig.getName() + "-" + r.getResourceType() + "-" + r.getId(), r));
					}
				}
			}
		}
		return arguments.stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("provideResources")
	public void testValidate(String name, Resource resource) throws Exception {
		OperationOutcome outcome = doValidate(name, resource);
		int fails = getValidationFailures(outcome);
		if (fails > 0) {
			log.error("failing " + name);
			for (final var issue : outcome.getIssue()) {
				log.debug(String.format("  [%s][%s] %s",
												issue.getSeverity().name(),
												issue.getCode().name(),
												issue.getDiagnostics()));
			}
			//log.debug(contextR4.newJsonParser().encodeResourceToString(resource));
			//log.debug(contextR4.newJsonParser().encodeResourceToString(outcome));
		}
		assertEquals(0, fails);
	}

	public OperationOutcome doValidate(String name, Resource resource) throws IOException {
		log.debug("validating resource " + resource.getId() + " with " + TARGET_SERVER);

		FhirContext contextR4 = FhirVersionEnum.R4.newContext();

		if (name.startsWith("ch.fhir.ig.ch-emed")) {
			// remove text from bundle
			resource = removeHtml(resource);
		}

		String content = new org.hl7.fhir.r4.formats.JsonParser().composeString(resource);
		String profile = null;

		if (name.startsWith("ch.fhir.ig.ch-elm")) {
			if (resource.getResourceType() == org.hl7.fhir.r4.model.ResourceType.Bundle) {
				profile = "http://fhir.ch/ig/ch-elm/StructureDefinition/ch-elm-document-strict";
			} 
			if (resource.getResourceType() == org.hl7.fhir.r4.model.ResourceType.DocumentReference) {
				profile = "http://fhir.ch/ig/ch-elm/StructureDefinition/PublishDocumentReferenceStrict";
			} 
			if (profile == null) {
				Assumptions.abort("Ignoring validation for " + name +" since no profile found");
			}
		} else {		
			profile = resource.getMeta().getProfile().get(0).getValue();
		}

		OperationOutcome outcome = (OperationOutcome) this.validationClient.validate(content, profile);
		if (outcome == null) {
			log.debug(contextR4.newXmlParser().encodeResourceToString(resource));
			log.error("should have a return element");
		} else {
			if (getValidationFailures(outcome) > 0) {
				log.debug(contextR4.newXmlParser().encodeResourceToString(resource));
				log.debug("Validation Errors " + getValidationFailures(outcome));
				log.error(contextR4.newXmlParser().encodeResourceToString(outcome));
			}
		}

		return outcome;
	}

	private static boolean exemptFile(String fn, String ig) {
		if (Utilities.existsInList(fn, "spec.internals", "version.info", "schematron.zip", "package.json")) {
			return true;
		}
		if (!ELM.equals(ig)) {
			return true;
		}
		// only validate bundles for EMED
		if (ELM.equals(ig) && !(fn.startsWith("Bundle") || fn.startsWith("DocumentReference"))) {
			return true;
		}
		if (ELM.equals(ig) && (fn.startsWith("Bundle-ex-findDocumentReferencesResponse"))) {
			return true;
		}
		if (ELM.equals(ig) && (fn.startsWith("DocumentReference-1-DocumentReferenceResponseFailed"))) {
			return true;
		}
		if (ELM.equals(ig) && (fn.startsWith("DocumentReference-1-DocumentReferenceResponseCompleted"))) {
			return true;
		}
		if (ELM.equals(ig) && (fn.startsWith("DocumentReference-1-DocumentReferenceResponseInProgress"))) {
			return true;
		}
		return false;
	}

	public static org.hl7.fhir.r4.model.Resource removeHtml(org.hl7.fhir.r4.model.Resource r) {
		if (r instanceof org.hl7.fhir.r4.model.DomainResource) {
			org.hl7.fhir.r4.model.DomainResource dr = (org.hl7.fhir.r4.model.DomainResource) r;
			dr.setText(null);
			for(org.hl7.fhir.r4.model.Resource c : dr.getContained()) {
				removeHtml(c);
			}
		}
		if (r instanceof org.hl7.fhir.r4.model.Bundle) {
			org.hl7.fhir.r4.model.Bundle dr = (org.hl7.fhir.r4.model.Bundle) r;
			for(org.hl7.fhir.r4.model.Bundle.BundleEntryComponent entry : dr.getEntry()) {
				removeHtml(entry.getResource());
			}
		}
		return r;
	}

	static private Map<String, byte[]> fetchByPackage(AppProperties.ImplementationGuide src, boolean examples)
		throws Exception {
		String thePackageUrl = src.getUrl();
		PackageLoaderSvc loader = new PackageLoaderSvc();
		InputStream inputStream = new ByteArrayInputStream(loader.loadPackageUrlContents(thePackageUrl));
		NpmPackage pi = NpmPackage.fromPackage(inputStream, null, true);
		return loadPackage(pi, examples);
	}

	static public Map<String, byte[]> loadPackage(NpmPackage pi, boolean examples) throws Exception {
		Map<String, byte[]> res = new HashMap<String, byte[]>();
		if (pi != null) {
			if (examples) {
				for (String s : pi.list("example")) {
					if (process(s)) {
						res.put(s, TextFile.streamToBytes(pi.load("example", s)));
					}
				}
			} else {
				for (String s : pi.list("package")) {
					if (process(s)) {
						res.put(s, TextFile.streamToBytes(pi.load("package", s)));
					}
				}
			}
		}
		return res;
	}

	static public boolean process(String file) {
		if (file == null) {
			return false;
		}
		if ("ig-r4.json".equals(file)) {
			return false;
		}
		if ("package.json".equals(file)) {
			return false;
		}
		return !file.startsWith("ConceptMap-");
	}
}
