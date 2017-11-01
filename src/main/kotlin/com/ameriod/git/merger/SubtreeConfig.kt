package com.ameriod.git.merger

import org.eclipse.jgit.transport.URIish

/**
 * A configuration about which input repository should be merged into which
 * target directory.
 */
class SubtreeConfig
/**
 * @param subtreeDirectory
 * *            the target directory into which the repository should be
 * *            merged, can be `"."` to not change the directory
 * *            layout on merge
 * *
 * @param fetchUri
 * *            the URI where the repository is located (a local one is
 * *            preferred while experimenting with conversion, so that it does
 * *            not have to be fetched multiple times)
 */
(val subtreeDirectory: String, val fetchUri: URIish) {

    private val repositoryName: String

    init {
        this.repositoryName = fetchUri.humanishName
        if (this.repositoryName.isEmpty()) {
            throw IllegalArgumentException(
                    "Could not determine repository name from fetch URI: " + fetchUri)
        }
    }

    val remoteName: String
        get() = fetchUri.humanishName
}