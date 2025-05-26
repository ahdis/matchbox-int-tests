package health.matchbox.engine;

import ca.uhn.fhir.jpa.packages.loader.PackageLoaderSvc;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.utilities.FhirPublication;
import org.hl7.fhir.utilities.FileUtilities;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test bench to load IGs in matchbox-engine and validate all their examples.
 *
 * @author Quentin Ligier
 **/
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IgValidationTests {
	private static final Logger log = LoggerFactory.getLogger(IgValidationTests.class);

	private static final String EMED = "https://fhir.ch/ig/ch-emed/5.0.0/package.tgz";
	private static final List<String> IGS = List.of(
		"https://fhir.ch/ig/ch-core/5.0.0/package.tgz",
		EMED
	);


	private final MatchboxEngine engine;

	public IgValidationTests() throws IOException, URISyntaxException {
		this.engine = this.getEngine();
		this.engine.initTxCache(System.getProperty("user.dir") + File.separator + "txCache");
		this.engine.getContext().setCachingAllowed(true);
		PackageLoaderSvc loader = new PackageLoaderSvc();
		for (final String ig : IGS) {
			InputStream inputStream = new ByteArrayInputStream(loader.loadPackageUrlContents(ig));
			this.engine.loadPackage(inputStream);
		}
		log.info("------- Initialized --------");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("provideResources")
	void testValidate(final String name, final Resource resource) throws Exception {
		log.info("Validating resource %s".formatted(name));
		if (!resource.getMeta().hasProfile()) {
			Assumptions.abort("No meta.profile found, unable to validate this resource");
		}
		final OperationOutcome outcome = this.engine.validate(resource,
																				resource.getMeta().getProfile().get(0).getValue());

		final List<OperationOutcome.OperationOutcomeIssueComponent> errors = outcome.getIssue().stream()
			.filter(issue -> OperationOutcome.IssueSeverity.FATAL == issue.getSeverity() || OperationOutcome.IssueSeverity.ERROR == issue.getSeverity())
			.toList();

		final Map<OperationOutcome.IssueSeverity, Long> count = outcome.getIssue().stream()
			.map(OperationOutcome.OperationOutcomeIssueComponent::getSeverity)
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		for (final var c : count.entrySet()) {
			log.info(c.getKey().name() + " " + c.getValue());
		}

		for (final var issue : outcome.getIssue()) {
			log.error(String.format("[%s][%s] %s",
											issue.getSeverity().name(),
											issue.getCode().name(),
											issue.getDetails().getText()));
		}
		assertTrue(errors.isEmpty());
	}

	public static org.hl7.fhir.r4.model.Resource removeHtml(org.hl7.fhir.r4.model.Resource r) {
		if (r instanceof final org.hl7.fhir.r4.model.DomainResource dr) {
			dr.setText(null);
			for (org.hl7.fhir.r4.model.Resource c : dr.getContained()) {
				removeHtml(c);
			}
		}
		if (r instanceof final org.hl7.fhir.r4.model.Bundle dr) {
			for (org.hl7.fhir.r4.model.Bundle.BundleEntryComponent entry : dr.getEntry()) {
				removeHtml(entry.getResource());
			}
		}
		return r;
	}

	public static Stream<Arguments> provideResources() throws Exception {
		List<Arguments> arguments = new ArrayList<>();
		for (final String ig : IGS) {
			if (!ig.equals(EMED)) {
				// only validate bundles for EMED
				continue;
			}
			Map<String, byte[]> source = fetchByPackage(ig, true);
			for (Map.Entry<String, byte[]> t : source.entrySet()) {
				String fn = t.getKey();
				if (!exemptFile(fn, ig)) {
					Resource r = null;
					if (fn.endsWith(".xml") && !fn.endsWith("template.xml"))
						r = new org.hl7.fhir.r4.formats.XmlParser().parse(new ByteArrayInputStream(t.getValue()));
					else if (fn.endsWith(".json") && !fn.endsWith("template.json"))
						r = new org.hl7.fhir.r4.formats.JsonParser().parse(new ByteArrayInputStream(t.getValue()));
					else if (fn.endsWith(".txt") || fn.endsWith(".map"))
						r = new org.hl7.fhir.r4.utils.StructureMapUtilities(null).parse(new String(t.getValue()), fn);
					else
						throw new Exception("Unsupported format for " + fn);
					r = removeHtml(r);
					if (r != null) {
						arguments.add(Arguments.of(ig + "-" + r.getResourceType() + "-" + r.getId(), r));
					}
				}
			}
		}
		return arguments.stream();
	}


	private static boolean exemptFile(String fn, String ig) {
		if (Utilities.existsInList(fn, "spec.internals", "version.info", "schematron.zip", "package.json")) {
			return true;
		}
		// only validate bundles for EMED
		if (EMED.equals(ig) && !fn.startsWith("Bundle")) {
			return true;
		}
		// https://chat.fhir.org/#narrow/channel/179252-IG-creation/topic/display-warnings.3A.20true.20-.3E.20wrong.20display.20values.3A.20error
		if (EMED.equals(ig) && fn.startsWith("Bundle-2-6-MedicationPrescription")) {
			return true;
		}
		return false;
	}

	private static Map<String, byte[]> fetchByPackage(String url, boolean examples) throws Exception {
		PackageLoaderSvc loader = new PackageLoaderSvc();
		InputStream inputStream = new ByteArrayInputStream(loader.loadPackageUrlContents(url));
		NpmPackage pi = NpmPackage.fromPackage(inputStream, null, true);
		return loadPackage(pi, examples);
	}

	public static Map<String, byte[]> loadPackage(NpmPackage pi, boolean examples) throws Exception {
		Map<String, byte[]> res = new HashMap<>();
		if (pi != null) {
			if (examples) {
				for (String s : pi.list("example")) {
					if (process(s)) {
						res.put(s, FileUtilities.streamToBytes(pi.load("example", s)));
					}
				}
			} else {
				for (String s : pi.list("package")) {
					if (process(s)) {
						res.put(s, FileUtilities.streamToBytes(pi.load("package", s)));
					}
				}
			}
		}
		return res;
	}

	public static boolean process(String file) {
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

	/**
	 * Initialize a R4 matchbox engine with no terminology server.
	 */
	private MatchboxEngine getEngine() throws IOException, URISyntaxException {
		final var newEngine = new MatchboxEngine.MatchboxEngineBuilder()
			.getEngineR4();
//		newEngine.setTerminologyServer("http://tx.fhir.org", null, FhirPublication.R4, false);
		newEngine.setTerminologyServer("http://tx.fhir.org", null, FhirPublication.R4, true);
		newEngine.getContext().setCanRunWithoutTerminology(false);
		newEngine.getContext().setNoTerminologyServer(false);
		newEngine.setDisplayWarnings(true);
		return newEngine;
	}
}
