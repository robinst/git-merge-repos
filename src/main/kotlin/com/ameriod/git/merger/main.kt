package com.ameriod.git.merger

import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.IOException
import java.net.URISyntaxException

private const val FILE = 0
private const val NEW_REPO_DIR = 1
private const val USERNAME = 2
private const val PASSWORD = 3

@Throws(IOException::class, GitAPIException::class, URISyntaxException::class)
fun main(args: Array<String>) {
    val start = System.currentTimeMillis()

    val subtreeConfigs = getSubtreeConfigs(args)

    val outputPath = File(getNewRepoDir(args)).absolutePath
    println("Started merging ${subtreeConfigs.size} repositories into one, output directory: $outputPath")

    val merger = RepoMerger(outputPath, subtreeConfigs, getCredentialsProvider(args))
    val mergedRefs = merger.run()
    val timeMs = System.currentTimeMillis() - start
    printIncompleteRefs(mergedRefs)
    println("Done merged, took $timeMs ms")
    println("Merged repository: $outputPath")
}

private fun printIncompleteRefs(mergedRefs: List<MergedRef>) {
    mergedRefs
            .filter { mergedRef ->
                !mergedRef.configsWithoutRef.isEmpty()
            }
            .map { mergedRef ->
                println("${mergedRef.refType} '${mergedRef.refName}' was not in: ${join(mergedRef.configsWithoutRef)}")
            }
}

private fun join(configs: Collection<SubtreeConfig>): String {
    val sb = StringBuilder()
    for (config in configs) {
        if (sb.isNotEmpty()) {
            sb.append(", ")
        }
        sb.append(config.remoteName)
    }
    return sb.toString()
}

@Throws(IllegalArgumentException::class)
private fun exitInvalidUsage(message: String) {
    System.err.println(message)
    throw IllegalArgumentException(message)
}

internal data class InputRepo(val url: String,
                              val directory: String)

internal fun getNewRepoDir(args: Array<String>): String {
    val dir = getArgAtIndex(args, NEW_REPO_DIR);
    if (dir == null) {
        exitInvalidUsage("invalid arg neew to provide the new repo name")
    }
    return dir!!
}

internal fun getSubtreeConfigs(args: Array<String>): List<SubtreeConfig> {
    val file = getArgAtIndex(args, FILE)
    if (file == null) {
        exitInvalidUsage("invalid arg need to provide the file with the repositories to be merged")
    }
    val json = File(args[FILE]).inputStream().bufferedReader().use { it.readText() }
    println("Repositories to merge: $json")
    val subtreeConfigs = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter<List<InputRepo>>(Types.newParameterizedType(List::class.java, InputRepo::class.java))
            .fromJson(json)!!
            .map { SubtreeConfig(it.directory, URIish(it.url)) }

    if (subtreeConfigs.isEmpty()) {
        exitInvalidUsage("invalid arg '${args[FILE]} need to have an file with the specified json format")
    } else {
        println("Merging ${subtreeConfigs.size} repositories")
    }
    return subtreeConfigs
}

internal fun getArgAtIndex(args: Array<String>, index: Int): String? = if (args.lastIndex < index) null else args[index]

internal fun getCredentialsProvider(args: Array<String>): UsernamePasswordCredentialsProvider? {
    val username = getArgAtIndex(args, USERNAME)
    val password = getArgAtIndex(args, PASSWORD)
    if ((username != null && password.isNullOrEmpty()) || (username.isNullOrEmpty() && password != null)) {
        exitInvalidUsage("error if providing a username and a password, you need both...")
    } else if (username != null && password != null) {
        return UsernamePasswordCredentialsProvider(username, password)
    }
    return null
}