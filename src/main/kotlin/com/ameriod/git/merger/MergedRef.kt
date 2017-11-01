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
            val messageBuilder = StringBuilder()
            messageBuilder.append("Merge ").append(refType).append(" '").append(refName)
                    .append("' from multiple repositories")
            messageBuilder.append("\n\n")
            messageBuilder.append("Repositories:")
            appendRepositoryNames(messageBuilder, configsWithRef)
            if (!configsWithoutRef.isEmpty()) {
                messageBuilder.append("\n\nRepositories without this ").append(refType).append(":")
                appendRepositoryNames(messageBuilder, configsWithoutRef)
            }
            messageBuilder.append("\n")
            val message = messageBuilder.toString()
            return message
        }

    private fun appendRepositoryNames(builder: StringBuilder,
                                      configs: Collection<SubtreeConfig>) {
        for (config in configs) {
            builder.append("\n\t")
            builder.append(config.remoteName)
        }
    }
}
