package com.ameriod.git.merger

import java.io.IOException
import java.util.ArrayList
import java.util.Arrays
import java.util.Date

import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheBuilder
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.errors.CorruptObjectException
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.RawParseUtils

/**
 * Merges the passed commit trees into one tree, adjusting directory structure
 * if necessary (depends on options from user).
 */
class SubtreeMerger(private val repository: Repository) {

    @Throws(IOException::class)
    fun crezateMergeCommit(parentCommits: Map<SubtreeConfig, RevCommit>, message: String): ObjectId {
        val latestIdent = getLatestPersonIdent(parentCommits.values)
        val treeDirCache = createTreeDirCache(parentCommits, message)
        val parentIds = ArrayList(parentCommits.values)
        val inserter = repository.newObjectInserter()
        val treeId = treeDirCache.writeTree(inserter)

        val repositoryUser = PersonIdent(repository)
        val ident = PersonIdent(repositoryUser, latestIdent!!.`when`.time,
                latestIdent.timeZoneOffset)
        val commitBuilder = CommitBuilder()
        commitBuilder.setTreeId(treeId)
        commitBuilder.author = ident
        commitBuilder.committer = ident
        commitBuilder.message = message
        commitBuilder.setParentIds(parentIds)
        val mergeCommit = inserter.insert(commitBuilder)
        inserter.flush()
        return mergeCommit

    }

    private fun getLatestPersonIdent(commits: Collection<RevCommit>): PersonIdent? {
        var latest: PersonIdent? = null
        for (commit in commits) {
            val ident = commit.committerIdent
            val `when` = ident.`when`
            if (latest == null || `when`.after(latest.`when`)) {
                latest = ident
            }
        }
        return latest
    }

    @Throws(IOException::class)
    private fun createTreeDirCache(parentCommits: Map<SubtreeConfig, RevCommit>,
                                   commitMessage: String): DirCache {

        val treeWalk = TreeWalk(repository)
        treeWalk.isRecursive = true
        addTrees(parentCommits, treeWalk)

        val builder = DirCache.newInCore().builder()
        while (treeWalk.next()) {
            val iterator = getSingleTreeIterator(treeWalk, commitMessage) ?: throw IllegalStateException(
                    "Tree walker did not return a single tree (should not happen): " + treeWalk.pathString)
            val path = Arrays.copyOf(iterator.entryPathBuffer,
                    iterator.entryPathLength)
            val entry = DirCacheEntry(path)
            entry.fileMode = iterator.entryFileMode
            entry.setObjectId(iterator.entryObjectId)
            builder.add(entry)
        }
        builder.finish()
        return builder.dirCache
    }

    @Throws(IOException::class)
    private fun addTrees(parentCommits: Map<SubtreeConfig, RevCommit>, treeWalk: TreeWalk) {
        for ((key, parentCommit) in parentCommits) {
            val directory = key.subtreeDirectory
            if ("." == directory) {
                treeWalk.addTree(parentCommit.tree)
            } else {
                val prefix = directory.toByteArray(RawParseUtils.UTF8_CHARSET)
                val treeParser = CanonicalTreeParser(prefix,
                        treeWalk.objectReader, parentCommit.tree)
                treeWalk.addTree(treeParser)
            }
        }
    }

    private fun getSingleTreeIterator(treeWalk: TreeWalk, commitMessage: String): AbstractTreeIterator? {
        var result: AbstractTreeIterator? = null
        val treeCount = treeWalk.treeCount
        for (i in 0..treeCount - 1) {
            val it = treeWalk.getTree(i, AbstractTreeIterator::class.java)
            if (it != null) {
                if (result != null) {
                    val msg = "Trees of repositories overlap in path '" + it.entryPathString + "'. " + "We can only merge non-overlapping trees, " + "so make sure the repositories have been prepared for that. " + "One possible way is to process each repository to move the root to a subdirectory first.\n" + "Current commit:\n" + commitMessage
                    throw IllegalStateException(msg)
                } else {
                    result = it
                }
            }
        }
        return result
    }
}
