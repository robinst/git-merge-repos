package org.nibor.git_merge_repos;

import java.util.Collection;

/**
 * Info about a merged branch/tag and in which input repositories it was
 * present/missing.
 */
public class MergedRef {

	private final String refType;
	private final String refName;
	private final Collection<SubtreeConfig> configsWithRef;
	private final Collection<SubtreeConfig> configsWithoutRef;

	public MergedRef(String refType, String refName, Collection<SubtreeConfig> configsWithRef,
			Collection<SubtreeConfig> configsWithoutRef) {
		this.refType = refType;
		this.refName = refName;
		this.configsWithRef = configsWithRef;
		this.configsWithoutRef = configsWithoutRef;
	}

	public String getRefType() {
		return refType;
	}

	public String getRefName() {
		return refName;
	}

	public Collection<SubtreeConfig> getConfigsWithoutRef() {
		return configsWithoutRef;
	}

	public String getMessage() {
		StringBuilder messageBuilder = new StringBuilder();
		messageBuilder.append("Merge ").append(refType).append(" '").append(refName)
				.append("' from multiple repositories");
		messageBuilder.append("\n\n");
		messageBuilder.append("Repositories:");
		appendRepositoryNames(messageBuilder, configsWithRef);
		if (!configsWithoutRef.isEmpty()) {
			messageBuilder.append("\n\nRepositories without this ").append(refType).append(":");
			appendRepositoryNames(messageBuilder, configsWithoutRef);
		}
		messageBuilder.append("\n");
		String message = messageBuilder.toString();
		return message;
	}

	private static void appendRepositoryNames(StringBuilder builder,
			Collection<SubtreeConfig> configs) {
		for (SubtreeConfig config : configs) {
			builder.append("\n\t");
			builder.append(config.getRemoteName());
		}
	}
}
