package de.jonherrmann.etf.test;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import de.interactive_instruments.properties.ClassifyingPropertyHolder;
import de.interactive_instruments.properties.Properties;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

import de.interactive_instruments.IFile;
import de.interactive_instruments.io.FilenameExtensionFilter;
import de.interactive_instruments.io.GmlAndXmlFilter;
import de.interactive_instruments.io.MultiFileFilter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
class DataDrivenTest {
	private final IFile[] dataFiles;
	private final IFile[] requestFiles;
	private final IFile[] expectedFiles;
	private final XmlUnitDetailFormatter formatter = new XmlUnitDetailFormatter();

	private final Set<String> ignoreNodes = new HashSet<String>() {{
		add("ExecutableTestSuite");
		add("startTimestamp");
		add("testTaskResult");
		add("version");
		add("author");
		add("creationDate");
		add("lastEditor");
		add("lastUpdateDate");
		add("resource");
		add("logPath");
		add("id");
		add("ref");
	}};

	public class DDTResBundle {
		final int pos;

		private DDTResBundle(final int pos) {
			this.pos = pos;
		}

		public String getTestRequest(final String testObjectId) {
			final java.util.Properties jp = new java.util.Properties();
			final IFile requestProperties = requestFiles[pos];
			assertNotNull(requestProperties);
			try {
				jp.load(new FileInputStream(requestProperties));
			} catch (final IOException e) {
				fail("Error loading property file ", e);
				return null;
			}
			final Properties properties = new Properties();
			jp.forEach((key, value) -> properties.setProperty((String) key, (String) value));
			final ClassifyingPropertyHolder arguments = properties.getFlattenedPropertiesByClassification("arguments");

			final StringBuilder requestBuilder = new StringBuilder().append("{").
					append("\"label\": \"Test ").append(pos).append(" - ").append(getName()).append("\",").
					append("\"executableTestSuiteIds\": [\""+properties.getProperty("etsIds")+"\"],").
					append("\"arguments\": {");
					final Iterator<Map.Entry<String, String>> it = arguments.iterator();
					while(it.hasNext()) {
						final Map.Entry<String, String> a = it.next();
						requestBuilder.append("\"").append(a.getKey()).append("\": \"").append(a.getValue()).append("\"");
						if(it.hasNext()) {
							requestBuilder.append(",");
						}
					}
					requestBuilder.append("},").
					append("\"testObject\": {").append("\"id\": \"").append(testObjectId).append("\"").append("}").
					append("}");
			return requestBuilder.toString();
		}


		public IFile getTestInput() {
			try {
				final IFile testInputFile = dataFiles[pos];
				assertNotNull(testInputFile);
				testInputFile.expectIsReadable();
				return testInputFile;
			} catch (final IOException e) {
				fail("Error loading test input file ", e);
				return null;
			}
		}

		public void compare(final IFile resultFile) {
			String expectedXml = null;
			try {
				expectedXml = new IFile(expectedFiles[pos]).readContent("UTF-8").toString();
			} catch (final IOException e) {
				fail("Could not read " + expectedFiles[pos].getAbsolutePath(), e);
			}
			String resultXml = null;
			try {
				resultXml = resultFile.readContent("UTF-8").toString();
			} catch (final IOException e) {
				fail("Could not read " + resultFile.getAbsolutePath());
			}

			final Diff diff = DiffBuilder.compare(Input.fromString(resultXml))
					.withTest(Input.fromString(expectedXml))
					.checkForSimilar().checkForIdentical()
					.ignoreComments()
					.ignoreWhitespace()
					.normalizeWhitespace()
					.ignoreElementContentWhitespace()
					.withNodeFilter(node -> ignoreNodes.contains(node.getNodeName()))
					.build();

			if (diff.hasDifferences()) {
				final Difference difference = diff.getDifferences().iterator().next();
				assertEquals(formatter.getControlDetailDescription(difference.getComparison()),
						formatter.getTestDetailDescription(difference.getComparison()));
			}
		}

		public String getName() {
			try {
				return IFile.sanitize(dataFiles[pos].getFilenameWithoutExt());
			} catch (IOException e) {
				return Integer.toString(pos);
			}
		}

		@Override
		public String toString() {
			return getName();
		}
	}

	private DataDrivenTest(final IFile ddtDirectory) {
		final MultiFileFilter xmlFileFilter = GmlAndXmlFilter.instance().filename();
		final FilenameExtensionFilter zipFileFilter = new FilenameExtensionFilter(".zip");
		final MultiFileFilter fileFilter = xmlFileFilter.or(zipFileFilter);
		dataFiles = ddtDirectory.secureExpandPathDown("data").listIFiles(fileFilter);
		requestFiles = ddtDirectory.secureExpandPathDown("request").listIFiles(new FilenameExtensionFilter(".properties"));
		expectedFiles = ddtDirectory.secureExpandPathDown("expected").listIFiles(new FilenameExtensionFilter(".xml"));
		assertTrue(dataFiles.length > 0, "No files found in test 'data' directory");
		assertTrue(requestFiles.length > 0, "No files found in the 'request' directory");
		assertTrue(expectedFiles.length > 0, "No files found in the 'expected' data directory");
		assertEquals(dataFiles.length, expectedFiles.length,
				"Number of test data files does not match the number of expected result files");
		assertEquals(requestFiles.length, expectedFiles.length,
				"Number of request files does not match the number of expected result files");
	}

	private ArrayList<DDTResBundle> getDDTResBundles() {
		return IntStream.range(0, dataFiles.length).mapToObj(DDTResBundle::new)
				.collect(Collectors.toCollection(() -> new ArrayList<>(dataFiles.length)));
	}

	public static List<DDTResBundle> createDDT(final IFile ddtDirectory) {
		return new DataDrivenTest(ddtDirectory).getDDTResBundles();
	}
}
