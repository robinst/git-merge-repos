package org.nibor.git_merge_repos;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;

/**
 * Fetches original repos, merges original branches/tags of different repos and
 * creates branches/tags that point to new merge commits.
 */
public class RepoMerger {

	private final List<SubtreeConfig> subtreeConfigs;
	private final Repository repository;

	public RepoMerger(String outputRepositoryPath,
			List<SubtreeConfig> subtreeConfigs) throws IOException {
		this.subtreeConfigs = subtreeConfigs;
		File file = new File(outputRepositoryPath);
		repository = new RepositoryBuilder().setWorkTree(file).build();
		if (!repository.getDirectory().exists()) {
			repository.create();
		}
	}

	public List<MergedRef> run() throws IOException, GitAPIException {
		fetch();
		List<MergedRef> mergedBranches = mergeBranches();
		List<MergedRef> mergedTags = mergeTags();
		List<MergedRef> mergedRefs = new ArrayList<>();
		mergedRefs.addAll(mergedBranches);
		mergedRefs.addAll(mergedTags);
		deleteOriginalRefs();
		resetToBranch();
		return mergedRefs;
	}

	private void fetch() throws GitAPIException {
		for (SubtreeConfig config : subtreeConfigs) {
			RefSpec branchesSpec = new RefSpec(
					"refs/heads/*:refs/heads/original/"
							+ config.getRemoteName() + "/*");
			RefSpec tagsSpec = new RefSpec("refs/tags/*:refs/tags/original/"
					+ config.getRemoteName() + "/*");
			Git git = new Git(repository);
			git.fetch().setRemote(config.getFetchUri().toPrivateString())
					.setRefSpecs(branchesSpec, tagsSpec).call();
		}
	}

	private List<MergedRef> mergeBranches() throws IOException {
		List<MergedRef> mergedRefs = new ArrayList<>();
		Collection<String> branches = getRefSet("refs/heads/original/");
		for (String branch : branches) {
			MergedRef mergedBranch = mergeBranch(branch);
			mergedRefs.add(mergedBranch);
		}
		return mergedRefs;
	}

	private List<MergedRef> mergeTags() throws IOException {
		List<MergedRef> mergedRefs = new ArrayList<>();
		Collection<String> tags = getRefSet("refs/tags/original/");
		for (String tag : tags) {
			MergedRef mergedTag = mergeTag(tag);
			mergedRefs.add(mergedTag);
		}
		return mergedRefs;
	}

	private void deleteOriginalRefs() throws IOException {
		try (RevWalk revWalk = new RevWalk(repository)) {
			Collection<Ref> refs = new ArrayList<>();
			RefDatabase refDatabase = repository.getRefDatabase();
			Map<String, Ref> originalBranches = refDatabase.getRefs("refs/heads/original/");
			Map<String, Ref> originalTags = refDatabase.getRefs("refs/tags/original/");
			refs.addAll(originalBranches.values());
			refs.addAll(originalTags.values());
			for (Ref originalRef : refs) {
				RefUpdate refUpdate = repository.updateRef(originalRef.getName());
				refUpdate.setForceUpdate(true);
				refUpdate.delete(revWalk);
			}
		}
	}

	private void resetToBranch() throws IOException, GitAPIException {
		Ref master = repository.getRef(Constants.R_HEADS + "master");
		if (master != null) {
			Git git = new Git(repository);
			git.reset().setMode(ResetType.HARD).setRef(master.getName()).call();
		}
	}

	private MergedRef mergeBranch(String branch) throws IOException {

		Map<SubtreeConfig, ObjectId> resolvedRefs = resolveRefs(
				"refs/heads/original/", branch);

		Map<SubtreeConfig, RevCommit> parentCommits = new LinkedHashMap<>();
		try (RevWalk revWalk = new RevWalk(repository)) {
			for (SubtreeConfig config : subtreeConfigs) {
				ObjectId objectId = resolvedRefs.get(config);
				if (objectId != null) {
					RevCommit commit = revWalk.parseCommit(objectId);
					parentCommits.put(config, commit);
				}
			}
		}

		MergedRef mergedRef = getMergedRef("branch", branch, parentCommits.keySet());

		ObjectId mergeCommit = new SubtreeMerger(repository).createMergeCommit(parentCommits,
				mergedRef.getMessage());

		RefUpdate refUpdate = repository.updateRef("refs/heads/" + branch);
		refUpdate.setNewObjectId(mergeCommit);
		refUpdate.update();

		return mergedRef;
	}

	private MergedRef mergeTag(String tagName) throws IOException {
		Map<SubtreeConfig, ObjectId> resolvedRefs = resolveRefs(
				"refs/tags/original/", tagName);

		// Annotated tag that should be used for creating the merged tag, null
		// if only lightweight tags exist
		RevTag referenceTag = null;
		Map<SubtreeConfig, RevCommit> parentCommits = new LinkedHashMap<>();

		try (RevWalk revWalk = new RevWalk(repository)) {
			for (Map.Entry<SubtreeConfig, ObjectId> entry : resolvedRefs
					.entrySet()) {
				SubtreeConfig config = entry.getKey();
				ObjectId objectId = entry.getValue();
				RevCommit commit;
				RevObject revObject = revWalk.parseAny(objectId);
				if (revObject instanceof RevCommit) {
					// Lightweight tag (ref points directly to commit)
					commit = (RevCommit) revObject;
				} else if (revObject instanceof RevTag) {
					// Annotated tag (ref points to tag object with message,
					// which in turn points to commit)
					RevTag tag = (RevTag) revObject;
					RevObject peeled = revWalk.peel(tag);
					if (peeled instanceof RevCommit) {
						commit = (RevCommit) peeled;

						if (referenceTag == null) {
							referenceTag = tag;
						} else {
							// We already have one, but use the last (latest)
							// tag as reference
							PersonIdent referenceTagger = referenceTag.getTaggerIdent();
							PersonIdent thisTagger = tag.getTaggerIdent();
							if (thisTagger != null && referenceTagger != null
									&& thisTagger.getWhen().after(referenceTagger.getWhen())) {
								referenceTag = tag;
							}
						}
					} else {
						String msg = "Peeled tag " + tag.getTagName()
								+ " does not point to a commit, but to the following object: "
								+ peeled;
						throw new IllegalStateException(msg);
					}
				} else {
					throw new IllegalArgumentException("Object with ID "
							+ objectId + " has invalid type for a tag: "
							+ revObject);
				}
				parentCommits.put(config, commit);
			}
		}

		MergedRef mergedRef = getMergedRef("tag", tagName, parentCommits.keySet());
		ObjectId mergeCommit = new SubtreeMerger(repository).createMergeCommit(parentCommits,
				mergedRef.getMessage());

		ObjectId objectToReference;
		if (referenceTag != null) {
			TagBuilder tagBuilder = new TagBuilder();
			tagBuilder.setTag(tagName);
			tagBuilder.setMessage(referenceTag.getFullMessage());
			tagBuilder.setTagger(referenceTag.getTaggerIdent());
			tagBuilder.setObjectId(mergeCommit, Constants.OBJ_COMMIT);
			try (ObjectInserter inserter = repository.newObjectInserter()) {
				objectToReference = inserter.insert(tagBuilder);
				inserter.flush();
			}
		} else {
			objectToReference = mergeCommit;
		}

		String ref = Constants.R_TAGS + tagName;
		RefUpdate refUpdate = repository.updateRef(ref);
		refUpdate.setExpectedOldObjectId(ObjectId.zeroId());
		refUpdate.setNewObjectId(objectToReference);
		Result result = refUpdate.update();
		if (result != Result.NEW) {
			throw new IllegalStateException("Creating tag ref " + ref + " for "
					+ objectToReference + " failed with result " + result);
		}

		return mergedRef;
	}

	private Collection<String> getRefSet(String prefix) throws IOException {
		Map<String, Ref> refs = repository.getRefDatabase().getRefs(prefix);
		TreeSet<String> result = new TreeSet<>();
		for (String refName : refs.keySet()) {
			String branch = refName.split("/", 2)[1];
			result.add(branch);
		}
		return result;
	}

	private Map<SubtreeConfig, ObjectId> resolveRefs(String refPrefix,
			String name) throws IOException {
		Map<SubtreeConfig, ObjectId> result = new LinkedHashMap<>();
		for (SubtreeConfig config : subtreeConfigs) {
			String repositoryName = config.getRemoteName();
			String remoteBranch = refPrefix + repositoryName + "/" + name;
			ObjectId objectId = repository.resolve(remoteBranch);
			if (objectId != null) {
				result.put(config, objectId);
			}
		}
		return result;
	}

	private MergedRef getMergedRef(String refType, String refName,
			Set<SubtreeConfig> configsWithRef) {
		LinkedHashSet<SubtreeConfig> configsWithoutRef = new LinkedHashSet<>(
				subtreeConfigs);
		configsWithoutRef.removeAll(configsWithRef);

		return new MergedRef(refType, refName, configsWithRef, configsWithoutRef);
	}

}
