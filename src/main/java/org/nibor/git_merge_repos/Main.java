package org.nibor.git_merge_repos;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;

/**
 * Main class for merging repositories via command-line.
 */
public class Main {
	private static final String USAGE = "usage: program <repository_url>:<target_directory>...";
	private static final Pattern REPO_AND_DIR = Pattern.compile("(.*):([^:]+)");

	public static void main(String[] args) throws IOException, GitAPIException, URISyntaxException {
		if (args.length >= 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
			exit(USAGE, 0);
		}

		List<SubtreeConfig> subtreeConfigs = new ArrayList<>();

		for (String arg : args) {
			Matcher matcher = REPO_AND_DIR.matcher(arg);
			if (matcher.matches()) {
				String repositoryUrl = matcher.group(1);
				String directory = matcher.group(2);
				SubtreeConfig config = new SubtreeConfig(directory, new URIish(repositoryUrl));
				subtreeConfigs.add(config);
			} else {
				exitInvalidUsage("invalid argument '" + arg
						+ "', expected '<repository_url>:<target_directory>'");
			}
		}

		if (subtreeConfigs.isEmpty()) {
			exitInvalidUsage(USAGE);
		}

		File outputDirectory = new File("merged-repo");
		String outputPath = outputDirectory.getAbsolutePath();
		if (outputDirectory.exists()) {
			exit("Error: Output directory already exists (please remove it and rerun): " + outputPath, 1);
		}
		System.out.println("Started merging " + subtreeConfigs.size()
				+ " repositories into one, output directory: " + outputPath);

		long start = System.currentTimeMillis();
		RepoMerger merger = new RepoMerger(outputDirectory, subtreeConfigs);
		List<MergedRef> mergedRefs = merger.run();
		long end = System.currentTimeMillis();

		long timeMs = (end - start);
		printIncompleteRefs(mergedRefs);
		System.out.println("Done, took " + timeMs + " ms");
		System.out.println("Merged repository: " + outputPath);

	}

	private static void printIncompleteRefs(List<MergedRef> mergedRefs) {
		for (MergedRef mergedRef : mergedRefs) {
			if (!mergedRef.getConfigsWithoutRef().isEmpty()) {
				System.out.println(mergedRef.getRefType() + " '" + mergedRef.getRefName()
						+ "' was not in: " + join(mergedRef.getConfigsWithoutRef()));
			}
		}
	}

	private static String join(Collection<SubtreeConfig> configs) {
		StringBuilder sb = new StringBuilder();
		for (SubtreeConfig config : configs) {
			if (sb.length() != 0) {
				sb.append(", ");
			}
			sb.append(config.getRemoteName());
		}
		return sb.toString();
	}

	private static void exitInvalidUsage(String message) {
		exit(message, 64);
	}

	private static void exit(String message, int status) {
		System.err.println(message);
		System.exit(status);
	}
}
