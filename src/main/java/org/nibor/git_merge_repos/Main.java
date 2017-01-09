package org.nibor.git_merge_repos;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;

/**
 * Main class for merging repositories via command-line.
 */
public class Main {

	public static void main(String[] args) throws IOException, GitAPIException, URISyntaxException {
		List<SubtreeConfig> subtreeConfigs = new ArrayList<>();

		String repo_data_file = args[0];
		String merged_repo = args[1];

		try (Stream<String> stream = Files.lines(Paths.get(repo_data_file))) {
			for (String line : (Iterable<String>) stream::iterator) {
				String[] items = line.split("\t");
				String repositoryUrl = items[0];
				String directory = items[1];

				SubtreeConfig config = new SubtreeConfig(directory, new URIish(repositoryUrl));
				subtreeConfigs.add(config);
			}
		}

		if (subtreeConfigs.isEmpty()) {
			exitInvalidUsage("usage: program <git repo data file path> <merged repo folder path>");
		}

		File outputDirectory = new File(merged_repo);
		String outputPath = outputDirectory.getAbsolutePath();
		System.out.println("Started merging " + subtreeConfigs.size()
				+ " repositories into one, output directory: " + outputPath);

		long start = System.currentTimeMillis();
		RepoMerger merger = new RepoMerger(outputPath, subtreeConfigs);
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
		System.err.println(message);
		System.exit(64);
	}
}
