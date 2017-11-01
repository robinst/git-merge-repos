package com.ameriod.git.merger

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.TreeSet

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.RefUpdate.Result
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec

/**
 * Fetches original repos, merges original branches/tags of different repos and
 * creates branches/tags that point to new merge commits.
 */
class RepoMerger @Throws(IOException::class)
constructor(outputRepositoryPath: String,
            private val subtreeConfigs: List<SubtreeConfig>,
            private val credentialsProvider: CredentialsProvider?) {
    private val repository: Repository

    init {
        val file = File(outputRepositoryPath)
        repository = RepositoryBuilder().setWorkTree(file).build()
        if (!repository.directory.exists()) {
            repository.create()
        }
    }

    @Throws(IOException::class, GitAPIException::class)
    fun run(): List<MergedRef> {
        fetch()
        val mergedBranches = mergeBranches()
        val mergedTags = mergeTags()
        val mergedRefs = ArrayList<MergedRef>()
        mergedRefs.addAll(mergedBranches)
        mergedRefs.addAll(mergedTags)
        deleteOriginalRefs()
        resetToBranch()
        return mergedRefs
    }

    @Throws(GitAPIException::class)
    private fun fetch() {
        for (config in subtreeConfigs) {
            val branchesSpec = RefSpec(
                    "refs/heads/*:refs/heads/original/${config.remoteName}/*")
            val tagsSpec = RefSpec("refs/tags/*:refs/tags/original/${config.remoteName}/*")
            val git = Git(repository)
            git.fetch()
                    .setCredentialsProvider(credentialsProvider)
                    .setRemote(config.fetchUri.toPrivateString())
                    .setRefSpecs(branchesSpec, tagsSpec).call()
        }
    }

    @Throws(IOException::class)
    private fun mergeBranches(): List<MergedRef> {
        val mergedRefs = ArrayList<MergedRef>()
        val branches = getRefSet("refs/heads/original/")
        for (branch in branches) {
            val mergedBranch = mergeBranch(branch)
            mergedRefs.add(mergedBranch)
        }
        return mergedRefs
    }

    @Throws(IOException::class)
    private fun mergeTags(): List<MergedRef> {
        val mergedRefs = ArrayList<MergedRef>()
        val tags = getRefSet("refs/tags/original/")
        for (tag in tags) {
            val mergedTag = mergeTag(tag)
            mergedRefs.add(mergedTag)
        }
        return mergedRefs
    }

    @Throws(IOException::class)
    private fun deleteOriginalRefs() {
        val revWalk = RevWalk(repository)
        val refs = ArrayList<Ref>()
        val refDatabase = repository.refDatabase
        val originalBranches = refDatabase.getRefs("refs/heads/original/")
        val originalTags = refDatabase.getRefs("refs/tags/original/")
        refs.addAll(originalBranches.values)
        refs.addAll(originalTags.values)
        for (originalRef in refs) {
            val refUpdate = repository.updateRef(originalRef.name)
            refUpdate.isForceUpdate = true
            refUpdate.delete(revWalk)
        }

    }

    @Throws(IOException::class, GitAPIException::class)
    private fun resetToBranch() {
        val master = repository.getRef(Constants.R_HEADS + "master")
        if (master != null) {
            val git = Git(repository)
            git.reset().setMode(ResetType.HARD).setRef(master.name).call()
        }
    }

    @Throws(IOException::class)
    private fun mergeBranch(branch: String): MergedRef {

        val resolvedRefs = resolveRefs("refs/heads/original/", branch)

        val parentCommits = LinkedHashMap<SubtreeConfig, RevCommit>()
        val revWalk = RevWalk(repository)
        for (config in subtreeConfigs) {
            val objectId = resolvedRefs[config]
            if (objectId != null) {
                val commit = revWalk.parseCommit(objectId)
                parentCommits.put(config, commit)
            }
        }

        val mergedRef = getMergedRef("branch", branch, parentCommits.keys)

        val mergeCommit = SubtreeMerger(repository).createMergeCommit(parentCommits,
                mergedRef.message)

        val refUpdate = repository.updateRef("refs/heads/" + branch)
        refUpdate.setNewObjectId(mergeCommit)
        refUpdate.update()

        return mergedRef
    }

    @Throws(IOException::class)
    private fun mergeTag(tagName: String): MergedRef {
        val resolvedRefs = resolveRefs("refs/tags/original/", tagName)

        // Annotated tag that should be used for creating the merged tag, null
        // if only lightweight tags exist
        var referenceTag: RevTag? = null
        val parentCommits = LinkedHashMap<SubtreeConfig, RevCommit>()

        val revWalk = RevWalk(repository)
        for ((config, objectId) in resolvedRefs) {
            val commit: RevCommit
            val revObject = revWalk.parseAny(objectId)
            if (revObject is RevCommit) {
                // Lightweight tag (ref points directly to commit)
                commit = revObject
            } else if (revObject is RevTag) {
                // Annotated tag (ref points to tag object with message,
                // which in turn points to commit)
                val tag = revObject
                val peeled = revWalk.peel(tag)
                if (peeled is RevCommit) {
                    commit = peeled

                    if (referenceTag == null) {
                        referenceTag = tag
                    } else {
                        // We already have one, but use the last (latest)
                        // tag as reference
                        val referenceTagger = referenceTag.taggerIdent
                        val thisTagger = tag.taggerIdent
                        if (thisTagger != null && referenceTagger != null
                                && thisTagger.`when`.after(referenceTagger.`when`)) {
                            referenceTag = tag
                        }
                    }
                } else {
                    val msg = "Peeled tag ${tag.name} does not point to a commit, but to the following object: $peeled"
                    throw IllegalStateException(msg)
                }
            } else {
                throw IllegalArgumentException("Object with ID $objectId has invalid type for a tag: $revObject")
            }
            parentCommits.put(config, commit)
        }


        val mergedRef = getMergedRef("tag", tagName, parentCommits.keys)
        val mergeCommit = SubtreeMerger(repository).createMergeCommit(parentCommits,
                mergedRef.message)

        val objectToReference: ObjectId
        if (referenceTag != null) {
            val tagBuilder = TagBuilder()
            tagBuilder.tag = tagName
            tagBuilder.message = referenceTag.fullMessage
            tagBuilder.tagger = referenceTag.taggerIdent
            tagBuilder.setObjectId(mergeCommit, Constants.OBJ_COMMIT)
            val inserter = repository.newObjectInserter()
            objectToReference = inserter.insert(tagBuilder)
            inserter.flush()

        } else {
            objectToReference = mergeCommit
        }

        val ref = Constants.R_TAGS + tagName
        val refUpdate = repository.updateRef(ref)
        refUpdate.setExpectedOldObjectId(ObjectId.zeroId())
        refUpdate.setNewObjectId(objectToReference)
        val result = refUpdate.update()
        if (result != Result.NEW) {
            throw IllegalStateException("Creating tag ref $ref for $objectToReference failed with result $result")
        }

        return mergedRef
    }

    @Throws(IOException::class)
    private fun getRefSet(prefix: String): Collection<String> {
        val refs = repository.refDatabase.getRefs(prefix)
        val result = TreeSet<String>()
        for (refName in refs.keys) {
            val branch = refName.split("/".toRegex(), 2).toTypedArray()[1]
            result.add(branch)
        }
        return result
    }

    @Throws(IOException::class)
    private fun resolveRefs(refPrefix: String,
                            name: String): Map<SubtreeConfig, ObjectId> {
        val result = LinkedHashMap<SubtreeConfig, ObjectId>()
        for (config in subtreeConfigs) {
            val repositoryName = config.remoteName
            val remoteBranch = "$refPrefix$repositoryName/$name"
            val objectId = repository.resolve(remoteBranch)
            if (objectId != null) {
                result.put(config, objectId)
            }
        }
        return result
    }

    private fun getMergedRef(refType: String, refName: String,
                             configsWithRef: Set<SubtreeConfig>): MergedRef {
        val configsWithoutRef = LinkedHashSet(
                subtreeConfigs)
        configsWithoutRef.removeAll(configsWithRef)

        return MergedRef(refType, refName, configsWithRef, configsWithoutRef)
    }

}
