package org.nibor.git_merge_repos;

import org.eclipse.jgit.transport.URIish;

/**
 * A configuration about which input repository should be merged into which
 * target directory.
 */
public class SubtreeConfig {

	private final String repositoryName;
	private final URIish fetchUri;
	private final String subtreeDirectory;

	/**
	 * @param subtreeDirectory
	 *            the target directory into which the repository should be
	 *            merged, can be <code>"."</code> to not change the directory
	 *            layout on merge
	 * @param fetchUri
	 *            the URI where the repository is located (a local one is
	 *            preferred while experimenting with conversion, so that it does
	 *            not have to be fetched multiple times)
	 */
	public SubtreeConfig(String subtreeDirectory, URIish fetchUri) {
		this.subtreeDirectory = subtreeDirectory;
		this.fetchUri = fetchUri;
		this.repositoryName = fetchUri.getHumanishName();
		if (this.repositoryName.isEmpty()) {
			throw new IllegalArgumentException(
					"Could not determine repository name from fetch URI: " + fetchUri);
		}
	}

	public String getRemoteName() {
		return fetchUri.getHumanishName();
	}

	public URIish getFetchUri() {
		return fetchUri;
	}

	public String getSubtreeDirectory() {
		return subtreeDirectory;
	}
}