package health.matchbox.engine;

import ch.ahdis.matchbox.engine.MatchboxEngine;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.utilities.FhirPublication;
import org.hl7.fhir.utilities.TextFile;
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
import java.io.IOException;
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
	private static final List<String> IGS = List.of(
		"/igs/ihe.formatcode.fhir#1.1.0.tgz",
		"/igs/ch.fhir.ig.ch-epr-term#2.0.10.tgz",
		"/igs/ch.fhir.ig.ch-core#4.0.1.tgz",
		"/igs/ch.fhir.ig.ch-emed#4.0.1.tgz",
		"/igs/ch.cara.fhir.epr.emed#1.0.0.tgz"
	);

	private final MatchboxEngine engine;

	public IgValidationTests() throws IOException, URISyntaxException {
		this.engine = this.getEngine();
		for (final String ig : IGS) {
			this.engine.loadPackage(getClass().getResourceAsStream(ig));
		}
		log.info("------- Initialized --------");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("provideResources")
	void testValidate(final String name, final Resource resource) throws Exception {
		log.info("Validating resource %s".formatted(name));
		if (!resource.getMeta().hasProfile()) {
			Assumptions.abort("No meta.profile found, unable to valide this resource");
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

	public static Stream<Arguments> provideResources() throws Exception {
		List<Arguments> arguments = new ArrayList<>();
		for (final String ig : IGS) {
			/*if (ig.contains("ch.fhir.ig.ch-emed")) {
				// The IG contains unvalidatable examples
				continue;
			}*/
			Map<String, byte[]> source = fetchByPackage(ig, true);
			for (Map.Entry<String, byte[]> t : source.entrySet()) {
				String fn = t.getKey();
				if (!exemptFile(fn)) {
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
						arguments.add(Arguments.of(ig + "-" + r.getResourceType() + "-" + r.getId(), r));
					}
				}
			}
		}
		return arguments.stream();
	}


	private static boolean exemptFile(String fn) {
		if (Utilities.existsInList(fn, "spec.internals", "version.info", "schematron.zip", "package.json",
											// CH EMED
											"MedicationRequest-MedReq-ChangeMedication" // Should be contained in the PADV CHANGE
		)) {
			return true;
		}

		return fn.startsWith("Bundle-") || fn.startsWith("Composition-");
	}

	private static Map<String, byte[]> fetchByPackage(String src, boolean examples) throws Exception {
		NpmPackage pi = NpmPackage.fromPackage(IgValidationTests.class.getResourceAsStream(src), null, true);
		return loadPackage(pi, examples);
	}

	public static Map<String, byte[]> loadPackage(NpmPackage pi, boolean examples) throws Exception {
		Map<String, byte[]> res = new HashMap<>();
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
		newEngine.setTerminologyServer("http://tx.fhir.org", null, FhirPublication.R4);
		newEngine.getContext().setCanRunWithoutTerminology(false);
		newEngine.getContext().setNoTerminologyServer(false);
		return newEngine;
	}
}
