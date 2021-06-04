/*
 * Copyright 2015-2021 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.launcher.listeners;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apiguardian.api.API;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;
import org.junit.platform.commons.util.StringUtils;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * {@code UniqueIdTrackingListener} is a {@link TestExecutionListener} that tracks
 * the {@linkplain TestIdentifier#getUniqueId() unique IDs} of all
 * {@linkplain TestIdentifier#isTest() tests} that were
 * {@linkplain #executionFinished executed} during the execution of the
 * {@link TestPlan} and generates a file containing the unique IDs once execution
 * of the {@code TestPlan} has {@linkplain #testPlanExecutionFinished(TestPlan)
 * finished}.
 *
 * <p>Tests are tracked regardless of their {@link TestExecutionResult}, and the
 * unique IDs are written to the output file, one ID per line, encoding using UTF-8.
 *
 * <p>The output file can be used to execute the same set of tests again without
 * having to query the user configuration for the test plan and without having to
 * perform test discovery again. This can be useful for test environments such as
 * within a native image &mdash; for example, a GraalVM native image &mdash; in
 * order to rerun the exact same tests from a standard JVM test run within a
 * native image.
 *
 * <h3>Configuration and Defaults</h3>
 *
 * <p>The {@code OUTPUT_DIR} is the directory in which this listener generates
 * the output file (the {@code OUTPUT_FILE}). The exact path of the generated file
 * is {@code OUTPUT_DIR}/{@code OUTPUT_FILE}.
 *
 * <p>The name of the {@code OUTPUT_FILE} defaults to {@link #DEFAULT_FILE_NAME},
 * but a custom file name can be set via the {@link #OUTPUT_FILE_PROPERTY_NAME}
 * configuration property.
 *
 * <p>The {@code OUTPUT_DIR} can be set to a custom directory via the
 * {@link #OUTPUT_DIR_PROPERTY_NAME} configuration property. Otherwise the following
 * algorithm is used to select a default output directory.
 *
 * <ul>
 * <li>If the current working directory of the Java process contains a file named
 * {@code pom.xml}, the output directory will be {@code ./target}, following the
 * conventions of Maven.</li>
 * <li>If the current working directory of the Java process contains a file with
 * the extension {@code .gradle} or {@code .gradle.kts}, the output directory
 * will be {@code ./build}, following the conventions of Gradle.</li>
 * <li>Otherwise, the current working directory of the Java process will be used
 * as the output directory.</li>
 * </ul>
 *
 * <p>For example, in a project using Gradle as the build tool, the file generated
 * by this listener would be {@code ./build/junit-platform-unique-test-ids.txt}
 * by default.
 *
 * <p>Configuration properties can be set via JVM system properties, via a
 * {@code junit-platform.properties} file in the root of the classpath, or as
 * JUnit Platform {@linkplain ConfigurationParameters configuration parameters}.
 *
 * @since 1.8
 */
@API(status = EXPERIMENTAL, since = "1.8")
public class UniqueIdTrackingListener implements TestExecutionListener {

	/**
	 * Property name used to enable the {@code UniqueIdTrackingListener}: {@value}
	 *
	 * <p>The {@code UniqueIdTrackingListener} is registered automatically via
	 * Java's {@link java.util.ServiceLoader} mechanism but disabled by default.
	 *
	 * <p>Set the value of this property to {@code true} to enable this listener.
	 */
	public static final String LISTENER_ENABLED_PROPERTY_NAME = "junit.platform.listeners.uid.tracking.enabled";

	/**
	 * Property name used to set the path to the output directory for the file
	 * generated by the {@code UniqueIdTrackingListener}: {@value}
	 *
	 * <p>For details on the default output directory, see the
	 * {@linkplain UniqueIdTrackingListener class-level Javadoc}.
	 */
	public static final String OUTPUT_DIR_PROPERTY_NAME = "junit.platform.listeners.uid.tracking.output.dir";

	/**
	 * Property name used to set the name of the file generated by the
	 * {@code UniqueIdTrackingListener}: {@value}
	 *
	 * <p>Defaults to {@link #DEFAULT_FILE_NAME}.
	 */
	public static final String OUTPUT_FILE_PROPERTY_NAME = "junit.platform.listeners.uid.tracking.output.file";

	/**
	 * The default name of the file generated by the {@code UniqueIdTrackingListener}: {@value}
	 *
	 * @see #OUTPUT_FILE_PROPERTY_NAME
	 */
	public static final String DEFAULT_FILE_NAME = "junit-platform-unique-test-ids.txt";

	private final Logger logger = LoggerFactory.getLogger(UniqueIdTrackingListener.class);

	private final List<String> uniqueIds = new ArrayList<>();

	private boolean enabled;

	@Override
	public void testPlanExecutionStarted(TestPlan testPlan) {
		this.enabled = testPlan.getConfigurationParameters().getBoolean(LISTENER_ENABLED_PROPERTY_NAME).orElse(false);
	}

	@Override
	public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
		if (this.enabled && testIdentifier.isTest()) {
			this.uniqueIds.add(testIdentifier.getUniqueId());
		}
	}

	@Override
	public void testPlanExecutionFinished(TestPlan testPlan) {
		if (this.enabled) {
			Path outputFile;
			try {
				outputFile = getOutputFile(testPlan.getConfigurationParameters());
			}
			catch (IOException ex) {
				logger.error(ex, () -> "Failed to create output file");
				// Abort since we cannot generate the file.
				return;
			}

			logger.debug(() -> "Writing unique IDs to output file " + outputFile.toAbsolutePath());
			try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))) {
				this.uniqueIds.forEach(writer::println);
				writer.flush();
			}
			catch (IOException ex) {
				logger.error(ex, () -> "Failed to write unique IDs to output file " + outputFile.toAbsolutePath());
			}
		}
	}

	private Path getOutputFile(ConfigurationParameters configurationParameters) throws IOException {
		String filename = configurationParameters.get(OUTPUT_FILE_PROPERTY_NAME).orElse(DEFAULT_FILE_NAME);
		Path outputFile = getOutputDir(configurationParameters).resolve(filename);

		if (Files.exists(outputFile)) {
			Files.delete(outputFile);
		}

		Files.createFile(outputFile);

		return outputFile;
	}

	Path getOutputDir(ConfigurationParameters configurationParameters) throws IOException {
		Path cwd = currentWorkingDir();
		Path outputDir;

		String customDir = configurationParameters.get(OUTPUT_DIR_PROPERTY_NAME).orElse(null);
		if (StringUtils.isNotBlank(customDir)) {
			outputDir = cwd.resolve(customDir);
		}
		else if (Files.exists(cwd.resolve("pom.xml"))) {
			outputDir = cwd.resolve("target");
		}
		else if (containsFilesWithExtensions(cwd, ".gradle", ".gradle.kts")) {
			outputDir = cwd.resolve("build");
		}
		else {
			outputDir = cwd;
		}

		if (!Files.exists(outputDir)) {
			Files.createDirectories(outputDir);
		}

		return outputDir;
	}

	/**
	 * Get the current working directory.
	 * <p>Package private for testing purposes.
	 */
	Path currentWorkingDir() {
		return Paths.get(".");
	}

	/**
	 * Determine if the supplied directory contains files with any of the
	 * supplied extensions.
	 */
	private boolean containsFilesWithExtensions(Path dir, String... extensions) throws IOException {
		return Files.find(dir, 1, //
			(path, basicFileAttributes) -> {
				if (basicFileAttributes.isRegularFile()) {
					for (String extension : extensions) {
						if (path.getFileName().toString().endsWith(extension)) {
							return true;
						}
					}
				}
				return false;
			}).findFirst().isPresent();
	}

}
