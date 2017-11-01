package com.ameriod.git.merger

import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.util.ArrayList
import java.util.regex.Pattern

private val REPO_AND_DIR = Pattern.compile("(.*):([^:]+)")

@Throws(IOException::class, GitAPIException::class, URISyntaxException::class)
fun main(args: Array<String>) {
    val subtreeConfigs = ArrayList<SubtreeConfig>()

    for (arg in args) {
        val matcher = REPO_AND_DIR.matcher(arg)
        if (matcher.matches()) {
            val repositoryUrl = matcher.group(1)
            val directory = matcher.group(2)
            val config = SubtreeConfig(directory, URIish(repositoryUrl))
            subtreeConfigs.add(config)
        } else {
            exitInvalidUsage("invalid argument '" + arg
                    + "', expected '<repository_url>:<target_directory>'")
        }
    }

    if (subtreeConfigs.isEmpty()) {
        exitInvalidUsage("usage: program <repository_url>:<target_directory>...")
    }

    val outputDirectory = File("merged-repo")
    val outputPath = outputDirectory.absolutePath
    println("Started merging " + subtreeConfigs.size
            + " repositories into one, output directory: " + outputPath)

    val start = System.currentTimeMillis()
    val merger = RepoMerger(outputPath, subtreeConfigs)
    val mergedRefs = merger.run()
    val end = System.currentTimeMillis()

    val timeMs = end - start
    printIncompleteRefs(mergedRefs)
    println("Done, took $timeMs ms")
    println("Merged repository: " + outputPath)
}

private fun printIncompleteRefs(mergedRefs: List<MergedRef>) {
    for (mergedRef in mergedRefs) {
        if (!mergedRef.configsWithoutRef.isEmpty()) {
            println(mergedRef.refType + " '" + mergedRef.refName
                    + "' was not in: " + join(mergedRef.configsWithoutRef))
        }
    }
}

private fun join(configs: Collection<SubtreeConfig>): String {
    val sb = StringBuilder()
    for (config in configs) {
        if (sb.length != 0) {
            sb.append(", ")
        }
        sb.append(config.remoteName)
    }
    return sb.toString()
}

private fun exitInvalidUsage(message: String) {
    System.err.println(message)
    System.exit(64)
}
