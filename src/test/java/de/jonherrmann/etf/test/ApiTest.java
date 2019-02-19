package de.jonherrmann.etf.test;

import de.interactive_instruments.IFile;
import de.interactive_instruments.exceptions.ExcUtils;
import de.interactive_instruments.properties.PropertyUtils;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.*;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ApiTest {
	private final static IFile ddtDirectory = new IFile("src/test/resources/ddt");
	private final static IFile outputDirectory = new IFile("build/tmp/ddt/results");
	private final static IFile tmpOutputDirectory = new IFile("build/tmp/ddt/tmp_outputs");

	@BeforeAll
	static void setUp() throws IOException {
		outputDirectory.ensureDir();
		tmpOutputDirectory.ensureDir();

		RestAssured.baseURI = PropertyUtils.getenvOrProperty("API_TEST_ENDPOINT", "http://localhost/etf-webapp");
		RestAssured.basePath = PropertyUtils.getenvOrProperty("API_TEST_PATH", "/v2");
		final String username = PropertyUtils.getenvOrProperty("API_TEST_USERNAME", null);
		if(username!=null) {
			RestAssured.authentication = basic(username, PropertyUtils.getenvOrProperty("API_TEST_PASSWORD",""));
		}
	}

	@BeforeEach
	void checkStatus() {
		when().
				head("/v2/heartbeat").
		then().
				statusCode(204).
				header("Service-Status", "GOOD");
	}

	@TestFactory
	Stream<DynamicTest> createTests() {
		return DataDrivenTest.createDDT(ddtDirectory).stream()
				.map(t -> DynamicTest.dynamicTest(
						"Data Test: " + t.getName(),
						() -> {
							final IFile testTmpOutputDirectory = tmpOutputDirectory.secureExpandPathDown(t.getName())
									.ensureDir();
							final IFile tmpOutputFile = new IFile(testTmpOutputDirectory, "TestRunResult.xml");

							// Upload file and create a temporary test object
							final String tempObjectId =
									given().
										queryParam("action", "upload").
										multiPart(t.getTestInput()).
									when().
										post("/TestObjects").
									then().
										statusCode(200).
										contentType(JSON).
										body("testObject", notNullValue()).
									extract().
										path("testObject.id");

							// Start test
							final String testRunId =
									when().
										post("/TestRuns", t.getTestRequest(tempObjectId)).
									then().
										statusCode(201).
										contentType(JSON).
										body("EtfItemCollection.returnedItems", is(1)).
									extract().
										path("EtfItemCollection.testRuns.TestRun.id");
							assertNotNull(testRunId);

							// Wait until finished
							for (int i = 0; i < 43200; i++) {
								if(finished(testRunId)) {
									break;
								}
							}

							// Download result
							final InputStream result = given().
									pathParam("testRunId", testRunId).
									get("/TestRuns/{testRunId}.xml").
									asInputStream();
							tmpOutputFile.writeContent(result, "UTF-8");

							// Compare
							t.compare(tmpOutputFile);
						}));
	}


	private boolean finished(final String testRunId) {
		try {
			TimeUnit.SECONDS.sleep(10);
		} catch (InterruptedException e) {
			ExcUtils.suppress(e);
		}
		final ValidatableResponse result =
				given().
						pathParam("testRunId", testRunId).
				when().
						get("/TestRuns/{testRunId}/progress").
				then().
						statusCode(200).
						contentType(JSON).
				body("max", notNullValue());
		final String max = result.extract().path("max");
		final String pos = result.extract().path("pos");
		return max.equals(pos);
	}
}
