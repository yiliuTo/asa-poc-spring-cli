/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cli.runtime.engine.frontmatter;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.springframework.cli.SpringCliException;
import org.springframework.cli.util.FileExtensionUtils;

/**
 * Reads the format of an app accelerator file enhanced with a {@link FrontMatter}.
 *
 * @author Mark Pollack
 */
public class FrontMatterReader {

	public static Optional<CommandActionFileContents> read(String templateContents) {
		return read(templateContents, "-");
	}

	private static Optional<CommandActionFileContents> read(String templateContents, String delimiter) {
		// Ignore empty files
		if (templateContents.isEmpty()) {
			return Optional.empty();
		}
		// Check if front matter section is present
		BufferedReader bufferedReader = new BufferedReader(new StringReader(templateContents));
		String line = null;
		StringBuilder stringBuilder = new StringBuilder();
		try {
			line = bufferedReader.readLine();
			assertLineNotNull(line);
			String regex = "[" + delimiter + "]{3,}";
			if (!line.matches(regex)) { // use at least three dashes or tildes
				return Optional.empty();
			}
			line = bufferedReader.readLine();
			assertLineNotNull(line);
			boolean foundEndingDelimiter = false;
			while (line != null && !foundEndingDelimiter) {
				stringBuilder.append(line);
				stringBuilder.append("\n");
				line = bufferedReader.readLine();
				if (line.matches(regex)) {
					foundEndingDelimiter = true;
				}
			}

			if (!foundEndingDelimiter) {
				return Optional.empty();
			}

			ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
					.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			FrontMatter frontMatter = mapper.readValue(stringBuilder.toString(), FrontMatter.class);

			String templateText = bufferedReader.lines().collect(Collectors.joining("\n"));

			return Optional.of(new CommandActionFileContents(frontMatter, templateText));
		}
		catch (Exception ex) {
			throw new SpringCliException("Could not read the action file with contents\n" + templateContents + "\n"
					+ "Parsed frontmatter contents = \n" + stringBuilder.toString(), ex);
		}
	}

	private static void assertLineNotNull(String line) {
		if (line == null) {
			throw new RuntimeException("Line read was null");
		}
	}

	public static Optional<CommandActionFileContents> read(Path path) {
		try {
			String delimiter = "-";
			boolean isYamlFile = false;
			String fileExtension = FileExtensionUtils.getExtension(path.toString());
			if (!fileExtension.isEmpty()) {
				if (fileExtension.equalsIgnoreCase("yaml") || fileExtension.equalsIgnoreCase(("yml"))) {
					isYamlFile = true;
				}
			}
			String fileContents;
			try {
				fileContents = Files.readAllLines(path).stream().collect(Collectors.joining("\n"));
			}
			catch (MalformedInputException e) {
				// ToDo: make this a more general solution
				// try another charset
				fileContents = Files.readAllLines(path, StandardCharsets.ISO_8859_1).stream()
						.collect(Collectors.joining("\n"));
			}
			try {
				return read(fileContents, delimiter);
			}
			catch (Exception ex) {
					throw new SpringCliException("Could not read action file in Path = " + path, ex);
			}
		}
		catch (Exception ex) {
			throw new SpringCliException("Could not read action file in Path = " + path, ex);
		}
	}

}
