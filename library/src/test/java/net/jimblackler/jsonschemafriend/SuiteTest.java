package net.jimblackler.jsonschemafriend;

import static net.jimblackler.jsonschemafriend.ResourceUtils.getResourceAsStream;
import static net.jimblackler.jsonschemafriend.Utils.getOrDefault;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class SuiteTest {
  public static final FileSystem FILE_SYSTEM = FileSystems.getDefault();
  private static final boolean WRITE_ALLOWLIST = false;

  private static Collection<DynamicNode> scan(
      Set<Path> testDirs, Path remotes, URI metaSchema, boolean runFormatTests) {
    ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    Collection<DynamicNode> allFileTests = new ArrayList<>();
    DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
    prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
    ObjectWriter objectWriter = objectMapper.writer(prettyPrinter);
    ObjectReader objectReader = objectMapper.reader();
    URL resource1 = ResourceUtils.getResource(SuiteTest.class, remotes.toString());
    UrlRewriter urlRewriter =
        in -> URI.create(in.toString().replace("http://localhost:1234", resource1.toString()));
    Validator validator = new Validator(runFormatTests);
    for (Path path : testDirs) {
      Collection<DynamicNode> dirTests = new ArrayList<>();
      try (InputStream inputStream = getResourceAsStream(SuiteTest.class, path.toString());
          BufferedReader bufferedReader =
              new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
        String resource;
        while ((resource = bufferedReader.readLine()) != null) {
          if (!resource.endsWith(".json")) {
            continue;
          }
          Collection<DynamicNode> nodes = new ArrayList<>();
          Path resourcePath = path.resolve(resource);
          URI testSourceUri =
              ResourceUtils.getResource(SuiteTest.class, resourcePath.toString()).toURI();
          Path allowListFolder =
              FILE_SYSTEM.getPath(path.toString().replaceFirst("/suites/", "suiteAllowList/"));
          Path allowListFile = allowListFolder.resolve(resource);

          InputStream allowListInStream = getResourceAsStream(SuiteTest.class, "/" + allowListFile);
          Map<String, Object> allowListIn =
              allowListInStream == null
                  ? new LinkedHashMap<>()
                  : objectReader.readValue(allowListInStream, Map.class);

          Map<String, Object> allowListOut = new LinkedHashMap<>();
          try (InputStream inputStream1 =
              getResourceAsStream(SuiteTest.class, resourcePath.toString())) {
            List<Object> data = (List<Object>) objectMapper.readValue(inputStream1, Object.class);
            for (int idx = 0; idx != data.size(); idx++) {
              Map<String, Object> testSet = (Map<String, Object>) data.get(idx);
              if (!testSet.containsKey("schema")) {
                continue; // ever happens?
              }

              Collection<DynamicTest> tests = new ArrayList<>();
              Object schemaObject = testSet.get("schema");
              if (schemaObject instanceof Map) {
                Map<String, Object> schema1 = (Map<String, Object>) schemaObject;
                if (!schema1.containsKey("$schema")) {
                  schema1.put("$schema", metaSchema.toString());
                }
              }
              List<Object> tests1 = (List<Object>) testSet.get("tests");
              String testsDescription = (String) testSet.get("description");
              for (int idx2 = 0; idx2 != tests1.size(); idx2++) {
                Map<String, Object> test = (Map<String, Object>) tests1.get(idx2);
                Object data1 = test.get("data");
                boolean valid = getOrDefault(test, "valid", false);
                String description = getOrDefault(test, "description", "Data: " + data1);
                tests.add(
                    dynamicTest(
                        valid ? description : description + "(F)",
                        testSourceUri,
                        () -> {
                          System.out.println("Schema:");
                          System.out.println(objectMapper.writeValueAsString(schemaObject));
                          System.out.println();

                          SchemaStore schemaStore = new SchemaStore(urlRewriter, true);
                          net.jimblackler.jsonschemafriend.Schema schema1 =
                              schemaStore.loadSchema(schemaObject);

                          System.out.println("Test:");
                          System.out.println(objectMapper.writeValueAsString(test));
                          System.out.println();

                          List<ValidationError> errors = new ArrayList<>();
                          validator.validate(schema1, data1, URI.create(""), errors::add);

                          System.out.print("Expected to " + (valid ? "pass" : "fail") + " ... ");
                          if (errors.isEmpty()) {
                            System.out.println("Passed");
                          } else {
                            System.out.println("Failures:");
                            for (ValidationError error : errors) {
                              System.out.println(error);
                            }
                            System.out.println();
                          }

                          if (errors.isEmpty() != valid) {
                            if (WRITE_ALLOWLIST) {
                              Map<String, Object> testsAllowList =
                                  (Map<String, Object>) allowListOut.get(testsDescription);
                              if (testsAllowList == null) {
                                testsAllowList = new LinkedHashMap<>();
                                allowListOut.put(testsDescription, testsAllowList);
                              }

                              testsAllowList.put(description, true);

                              Files.createDirectories(allowListFolder);
                              try (FileWriter w = new FileWriter(allowListFile.toFile())) {
                                objectWriter.writeValue(w, allowListOut);
                              }
                              fail();
                            } else {
                              Map<String, Object> testsAllowListIn =
                                  (Map<String, Object>) allowListIn.get(testsDescription);
                              if (testsAllowListIn != null
                                  && testsAllowListIn.containsKey(description)) {
                                assumeTrue(false);
                              } else {
                                fail();
                              }
                            }
                          }
                        }));
              }
              nodes.add(dynamicContainer(testsDescription, testSourceUri, tests.stream()));
            }
          }
          dirTests.add(dynamicContainer(resource, testSourceUri, nodes.stream()));
        }
        allFileTests.add(
            dynamicContainer(path.getName(path.getNameCount() - 1).toString(), dirTests));
      } catch (IOException | URISyntaxException e) {
        throw new IllegalStateException(e);
      }
    }

    return allFileTests;
  }

  private static Collection<DynamicNode> test(
      String set, String metaSchema, boolean runFormatTests) {
    Path suite = FILE_SYSTEM.getPath("/suites").resolve("JSON-Schema-Test-Suite");
    Path tests = suite.resolve("tests").resolve(set);
    Path optional = tests.resolve("optional");
    Set<Path> paths = new LinkedHashSet<>();
    paths.add(tests);
    paths.add(optional);
    paths.add(optional.resolve("format"));
    Path remotes = suite.resolve("remotes");
    return scan(paths, remotes, URI.create(metaSchema), runFormatTests);
  }

  @TestFactory
  Collection<DynamicNode> own() {
    Path path = FILE_SYSTEM.getPath("/suites");
    Path own = path.resolve("own");
    Set<Path> testDirs = new LinkedHashSet<>();
    testDirs.add(own);
    Path remotes = path.resolve("own_remotes");
    URI metaSchema = URI.create("http://json-schema.org/draft-07/schema#");
    return scan(testDirs, remotes, metaSchema, false);
  }

  @TestFactory
  Collection<DynamicNode> draft3() {
    return test("draft3", "http://json-schema.org/draft-03/schema#", false);
  }

  @TestFactory
  Collection<DynamicNode> draft4() {
    return test("draft4", "http://json-schema.org/draft-04/schema#", false);
  }

  @TestFactory
  Collection<DynamicNode> draft6() {
    return test("draft6", "http://json-schema.org/draft-06/schema#", false);
  }

  @TestFactory
  Collection<DynamicNode> draft7() {
    return test("draft7", "http://json-schema.org/draft-07/schema#", false);
  }

  @TestFactory
  Collection<DynamicNode> draft2019_09() {
    return test("draft2019-09", "https://json-schema.org/draft/2019-09/schema", true);
  }

  @TestFactory
  Collection<DynamicNode> draft2020_12() {
    return test("draft2020-12", "https://json-schema.org/draft/2020-12/schema", true);
  }
}
