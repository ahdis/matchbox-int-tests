package health.matchbox.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.packages.loader.PackageLoaderSvc;
import ca.uhn.fhir.jpa.starter.AppProperties.ImplementationGuide;
import ch.ahdis.matchbox.util.PackageCacheInitializer;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.exceptions.FHIRFormatError;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * see https://www.baeldung.com/springjunit4classrunner-parameterized read the implementation guides defined in ig and
 * execute the validations
 * <p>
 * when running the testsuite we have sometime spurious errors during startup and alos during proccessing (Bundles to
 * big?))
 * <p>
 * UPDATE 2023/04/13 QL: This class was used as a base for IgValidateR4Test but it has been refactored for JUnit5.
 *
 * @author oliveregger
 */
@ActiveProfiles("test-ch")
@Disabled
public class IgValidateR4TestStandalone {

	static private final Set<String> loadedIgs = new HashSet<String>();

	private final Resource resource;
	private final String name;
	private final String targetServer;

	public static List<ImplementationGuide> getImplementationGuides() {
		Yaml yaml = new Yaml();
		InputStream inputStream = null;
		try {
			ClassPathResource resource = new ClassPathResource("application-test-ch.yaml");
			inputStream = new FileInputStream(resource.getFile());
		} catch (IOException e) {
			return null;
		}
		Map<String, Object> obj = yaml.load(inputStream);
		return PackageCacheInitializer.getIgs(obj, true);
	}

	public static Iterable<Object[]> data() throws ParserConfigurationException, IOException, FHIRFormatError {

		List<ImplementationGuide> igs = getImplementationGuides();
		List<Object[]> objects = new ArrayList<Object[]>();
		for (ImplementationGuide ig : igs) {
			List<Resource> resources = getResources(ig);
			for (Resource fn : resources) {
				String name = ig.getName() + "-" + fn.getResourceType() + "-" + fn.getId();
				objects.add(new Object[]{name, fn});
			}
		}
		return objects;
	}

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IgValidateR4TestStandalone.class);

	static private Map<String, byte[]> fetchByPackage(ImplementationGuide src, boolean examples) throws Exception {
		String thePackageUrl = src.getUrl();
		PackageLoaderSvc loader = new PackageLoaderSvc();
		InputStream inputStream = new ByteArrayInputStream(loader.loadPackageUrlContents(thePackageUrl));
		NpmPackage pi = NpmPackage.fromPackage(inputStream, null, true);
		return loadPackage(pi, examples);
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

	static private boolean exemptFile(String fn) {
		return Utilities.existsInList(fn, "spec.internals", "version.info", "schematron.zip", "package.json");
	}

	static public List<Resource> loadIg(ImplementationGuide src, boolean examples)
		throws Exception {
		List<Resource> resources = new ArrayList<Resource>();
		try {
			Map<String, byte[]> source = fetchByPackage(src, examples);
			String version = "4.0.1";
			for (Entry<String, byte[]> t : source.entrySet()) {
				String fn = t.getKey();
				if (!exemptFile(fn)) {
					Resource r = loadFileWithErrorChecking(version, t, fn);
					if (r != null) {
						resources.add(r);
					}
				}
			}
		} catch (java.io.FileNotFoundException e) {

		}
		return resources;
	}

	static public Resource loadFileWithErrorChecking(String version, Entry<String, byte[]> t, String fn) {
		Resource r = null;
		try {
			r = loadResourceByVersion(version, t.getValue(), fn);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return r;
	}

	static public Resource loadResourceByVersion(String version, byte[] content, String fn)
		throws Exception {
		Resource r = null;
		if (version.startsWith("4.0")) {
			if (fn.endsWith(".xml") && !fn.endsWith("template.xml"))
				r = new org.hl7.fhir.r4.formats.XmlParser().parse(new ByteArrayInputStream(content));
			else if (fn.endsWith(".json") && !fn.endsWith("template.json"))
				r = new org.hl7.fhir.r4.formats.JsonParser().parse(new ByteArrayInputStream(content));
			else if (fn.endsWith(".txt") || fn.endsWith(".map"))
				r = new org.hl7.fhir.r4.utils.StructureMapUtilities(null).parse(new String(content), fn);
			else
				throw new Exception("Unsupported format for " + fn);
		} else
			throw new Exception("Unsupported version " + version);
		return r;
	}

	static private List<Resource> getResources(ImplementationGuide implementationGuide) {
		List<Resource> resources = null;
		try {
			resources = loadIg(implementationGuide, true);
		} catch (Exception e) {
			log.error("error loading R4 or ImplementationGuide", e);
			return null;
		}

		return resources;
	}

	public IgValidateR4TestStandalone(String name, Resource resource, String targetServer) {
		super();
		this.resource = resource;
		this.name = name;
		this.targetServer = targetServer;
	}

	@Test
	public void validate() throws Exception {
		OperationOutcome outcome = validate(resource);
		int fails = getValidationFailures(outcome);
		if (fails > 0) {
			assertEquals("success", outcome.toString());
		}
	}

	static public int getValidationFailures(OperationOutcome outcome) {
		int fails = 0;
		if (outcome != null && outcome.getIssue() != null) {
			for (OperationOutcomeIssueComponent issue : outcome.getIssue()) {
				if (IssueSeverity.FATAL == issue.getSeverity()) {
					++fails;
				}
				if (IssueSeverity.ERROR == issue.getSeverity()) {
					++fails;
				}
			}
		}
		return fails;
	}

	public OperationOutcome validate(Resource resource) throws IOException {
		return validate(resource, this.targetServer);
	}


	public OperationOutcome validate(Resource resource, String targetServer) throws IOException {
		log.debug("validating resource" + resource.getId() + "with" + targetServer);
		FhirContext contextR4 = FhirVersionEnum.R4.newContext();

		boolean skip = "ch.fhir.ig.ch-core#1.0.0-PractitionerRole-HPWengerRole".equals(name); // wrong value inside
		skip = skip || "ch.fhir.ig.ch-epr-mhealth#0.1.2-Bundle-2-7-BundleProvideDocument".equals(name); // error in testcase, however cannot reproduce yet directly ???
		if (skip) {
			log.error("ignoring validation for " + name);
			Assumptions.assumeFalse(skip);
		}

		ValidationClient genericClient = new ValidationClient(contextR4, targetServer);

		String content = new org.hl7.fhir.r4.formats.JsonParser().composeString(resource);
		OperationOutcome outcome = (OperationOutcome) genericClient.validate(content,
																									resource.getMeta().getProfile().get(0).getValue());

		if (outcome == null) {
			log.debug(contextR4.newXmlParser().encodeResourceToString(resource));
			log.error("should have a return element");
			log.error(contextR4.newXmlParser().encodeResourceToString(outcome));
		} else {
			if (getValidationFailures(outcome) > 0) {
				log.debug(contextR4.newXmlParser().encodeResourceToString(resource));
				log.debug("Validation Errors " + getValidationFailures(outcome));
				log.error(contextR4.newXmlParser().encodeResourceToString(outcome));
			}
		}

		return outcome;
	}

	private static boolean hasParam(String[] args, String param) {
		for (String a : args)
			if (a.equals(param))
				return true;
		return false;
	}

	private static String getParam(String[] args, String param) {
		for (int i = 0; i < args.length - 1; i++)
			if (args[i].equals(param))
				return args[i + 1];
		return null;
	}

}
