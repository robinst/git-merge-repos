package com.ameriod.git.merger

/**
 * Info about a merged branch/tag and in which input repositories it was
 * present/missing.
 */
class MergedRef(val refType: String,
                val refName: String,
                private val configsWithRef: Collection<SubtreeConfig>,
                val configsWithoutRef: Collection<SubtreeConfig>) {

    val message: String
        get() {
            val messageBuilder =
                    appendRepositoryNames(StringBuilder("Merge $refType '$refName' from multiple repositories\n\n Repositories:"), configsWithRef)
            if (!configsWithoutRef.isEmpty()) {
                appendRepositoryNames(messageBuilder
                        .append("\n\nRepositories without this $refType:"), configsWithoutRef)
            }
            return messageBuilder
                    .append("\n")
                    .toString()
        }

    private fun appendRepositoryNames(builder: StringBuilder,
                                      configs: Collection<SubtreeConfig>): StringBuilder {
        for (config in configs) {
            builder.append("\n\t")
            builder.append(config.remoteName)
        }
        return builder
    }
}
