package health.matchbox.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.jpa.packages.loader.PackageLoaderSvc;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.util.PackageCacheInitializer;
import health.matchbox.util.ValidationClient;

import org.hl7.fhir.DomainResource;
import org.hl7.fhir.exceptions.FHIRFormatError;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.TestScript.TestActionComponent;
import org.hl7.fhir.r4.model.TestScript;
import org.hl7.fhir.r4.model.TestScript.TestScriptTestComponent;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Named;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.w3._1999.xhtml.A;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.commons.codec.binary.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * see https://www.baeldung.com/springjunit4classrunner-parameterized read the
 * implementation guides defined in ig and
 * execute the validations
 * <p>
 * It uses the port 8082.
 *
 * @author oliveregger
 **/
public class IgValidateR4 {


	private static final String TARGET_SERVER = "http://localhost:8082/matchboxv3/fhir";
	private static final Logger log = LoggerFactory.getLogger(IgValidateR4.class);
	@Autowired
	ApplicationContext context;
	private ValidationClient validationClient;

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

		String propertyString =  "";
		ActiveProfiles classAnnotation = this.getClass().getAnnotation(ActiveProfiles.class);
		if (classAnnotation != null) {
			propertyString = classAnnotation.value()[0];
		} else {
			log.error("property not found in @ActiveProfile annotation");
			return null;
		}
		String path =  "/application-" + propertyString + ".yaml";
	  
		Map<String, Object> obj = new Yaml().load(getClass().getResourceAsStream(path));
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
						if (r instanceof TestScript) {
							TestScript testScript = (TestScript) r;
							testScript.getFixture().forEach(fixture -> {
								if (fixture.getResource() != null && fixture.getResource().getReference() != null) {
									String ref = fixture.getResource().getReference();
									Resource resource = null;
									if (!ref.startsWith("http") || !ref.startsWith("#") || !ref.startsWith("urn")) {
											String refchangelink = ref.replace("/", "-")+".json";
											resource = source.entrySet().stream()
													.filter(e -> e.getKey().equals(refchangelink)).map(e -> {
														try {
															return new org.hl7.fhir.r4.formats.JsonParser()
																	.parse(new ByteArrayInputStream(e.getValue()));
														} catch (FHIRFormatError | IOException e1) {
															log.error("error parsing " + e.getKey(), e1);
															return null;
														}
													}).findFirst().get();
										if (resource != null) {
											resource.setId(fixture.getId());
											testScript.getContained().add(resource);
											fixture.getResource().setReference("#" + fixture.getId());
										}
									}
								}
							});
						}
						String name = ig.getName() + "-" + r.getResourceType() + "-" + r.getId();
						arguments.add(Arguments.of(name, r));
					}
				}
			}
		}
		return arguments.stream();
	}
	
	public void testValidate(String name, Resource resource) throws Exception {
		if (resource instanceof TestScript) {
			runTestScript(name, (TestScript) resource);
			return ;
		}
		OperationOutcome outcome = doValidate(name, resource);
		int fails = getValidationFailures(outcome);
		if (fails > 0) {
			String responseInJson = new org.hl7.fhir.r4.formats.JsonParser().composeString(outcome);
			String resourceInJson = new org.hl7.fhir.r4.formats.JsonParser().composeString(resource);
			assertEquals(0, fails, "Validation Errors " + fails + "\noutcome:\n" + responseInJson + "\nresource\n" + resourceInJson);
		}
		assertEquals(0, fails);
	}

	public OperationOutcome validate(TestScript resource, TestActionComponent action) throws IOException {
		String content = null;
		String reference = action.getOperation().getSourceId();
		Resource input = resource.getContained().stream().filter(r -> r.getId().equals(reference)).findFirst().get();
		if (input instanceof Binary) {
			content = new String(((Binary) input).getData(), StandardCharsets.UTF_8);
		} else {
			content = new org.hl7.fhir.r4.formats.JsonParser().composeString(input);
		}
		String params = action.getOperation().getParams();
		String profile = null;
		Optional<String> optProfile = Arrays.stream(params.split("&")).filter(p -> p.startsWith("profile=")).map(e -> e.substring(8)).findFirst();
		if (optProfile.isPresent()) {
			profile = optProfile.get();
		} else {
			fail("missing profile parameter in params " + params);
		}
		OperationOutcome outcome = (OperationOutcome) this.validationClient.validate(content, profile);
		return outcome;
	}

	public void runTestScript(String name, TestScript resource) throws Exception {
		// for each test in resource run the test
		FhirContext contextR4 = FhirVersionEnum.R4.newContextCached();
		IFhirPath fhirPath = contextR4.newFhirPath();
		Resource response = null;		
		String responseInJson = null;
		for (TestScriptTestComponent test: resource.getTest()) {
			for (TestActionComponent action: test.getAction()) {
				if (action.hasOperation()) {
					if (action.getOperation().getType()!=null && action.getOperation().getType().getCode().equals("validate")) {
						response = validate(resource, action);
						responseInJson = new org.hl7.fhir.r4.formats.JsonParser().composeString(response);
					} else {
						fail("unsupported operation " + action.getOperation());
					}
				} 
				if (action.hasAssert()) {
					if (action.getAssert().hasResponseCode()) {
						if (action.getAssert().getResponseCode().equals("200")) {
							assertNotNull(response);
						} else {
							fail("unsupported response code, not implemented yet " + action.getAssert().getResponseCode());
						}
						continue;
					}
					if (action.getAssert().hasExpression()) {
						String expressionFhirPath = action.getAssert().getExpression();
						try {
							assertEquals(action.getAssert().getValue(), fhirPath.evaluateFirst(response, expressionFhirPath, IPrimitiveType.class).get().getValueAsString(), "expression:\n"+expressionFhirPath+"\nresource:\n"+responseInJson);
							continue;
						} catch (ca.uhn.fhir.fhirpath.FhirPathExecutionException e) {
							fail("error evaluating expression " + expressionFhirPath  + e.getMessage());
						}
					}
					fail("unsupported assert, not implemented yet " + action.getAssert());
				}
			}
		}
	}

	public OperationOutcome doValidate(String name, Resource resource) throws IOException {
		log.debug("validating resource " + resource.getId() + " with " + TARGET_SERVER);

		FhirContext contextR4 = FhirVersionEnum.R4.newContext();

		if (name.startsWith("ch.fhir.ig.ch-emed")) {
			// remove text from bundle
			resource = removeHtml(resource);
		}

		String content = new org.hl7.fhir.r4.formats.JsonParser().composeString(resource);
		String profile = determineProfileToValidate(name, resource);

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

	protected String determineProfileToValidate(String name, Resource resource) {
		if (resource.getMeta()!=null && resource.getMeta().getProfile()!=null && resource.getMeta().getProfile().size()>0) {
			return resource.getMeta().getProfile().get(0).getValue();
		}
		return  "http://hl7.org/fhir/StructureDefinition/"+resource.getResourceType();	
	}

	protected boolean exemptFile(String fn, String ig) {
		if (Utilities.existsInList(fn, "spec.internals", "version.info", "schematron.zip", "package.json")) {
			return true;
		}
		if ((fn.startsWith("StructureDefinition") || fn.startsWith("ValueSet") || fn.startsWith("CodeSystem") || fn.startsWith("Parameters") || fn.startsWith("OperationDefinition"))) {
			return true;
		}
		if (ig.startsWith("hl7.fhir.uv.extensions")) {
			return true;
		}
		return false;
	}

	public static org.hl7.fhir.r4.model.Resource removeHtml(org.hl7.fhir.r4.model.Resource r) {
		if (r instanceof org.hl7.fhir.r4.model.DomainResource) {
			org.hl7.fhir.r4.model.DomainResource dr = (org.hl7.fhir.r4.model.DomainResource) r;
			dr.setText(null);
			for (org.hl7.fhir.r4.model.Resource c : dr.getContained()) {
				removeHtml(c);
			}
		}
		if (r instanceof org.hl7.fhir.r4.model.Bundle) {
			org.hl7.fhir.r4.model.Bundle dr = (org.hl7.fhir.r4.model.Bundle) r;
			for (org.hl7.fhir.r4.model.Bundle.BundleEntryComponent entry : dr.getEntry()) {
				removeHtml(entry.getResource());
			}
		}
		return r;
	}

	static private Map<String, byte[]> fetchByPackage(AppProperties.ImplementationGuide src, boolean examples)
			throws Exception {
		String thePackageUrl = src.getUrl();
		if (thePackageUrl == null) {
			return new HashMap<String, byte[]>();
		}
		PackageLoaderSvc loader = new PackageLoaderSvc();
		InputStream inputStream = new ByteArrayInputStream(loader.loadPackageUrlContents(thePackageUrl));
		NpmPackage pi = NpmPackage.fromPackage(inputStream, null, true);
		return loadPackage(pi, examples, true);
	}

	static public Map<String, byte[]> loadPackage(NpmPackage pi, boolean examples, boolean canonical) throws Exception {
		Map<String, byte[]> res = new HashMap<String, byte[]>();
		if (pi != null) {
			if (examples) {
				for (String s : pi.list("example")) {
					if (process(s)) {
						res.put(s, TextFile.streamToBytes(pi.load("example", s)));
					}
				}
			} 
			if (canonical) {
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
