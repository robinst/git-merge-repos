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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
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

	public void run() throws IOException, GitAPIException {
		fetch();
		mergeBranches();
		mergeTags();
		deleteOriginalRefs();
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

	private void mergeBranches() throws IOException {
		Collection<String> branches = getRefSet("refs/heads/original/");
		for (String branch : branches) {
			mergeBranch(branch);
		}
	}

	private void mergeTags() throws IOException {
		Collection<String> tags = getRefSet("refs/tags/original/");
		for (String tag : tags) {
			mergeTag(tag);
		}
	}

	private void deleteOriginalRefs() throws IOException {
		RevWalk revWalk = new RevWalk(repository);
		try {
			Collection<Ref> refs = new ArrayList<Ref>();
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
		} finally {
			revWalk.release();
		}
	}

	private void mergeBranch(String branch) throws AmbiguousObjectException,
			IncorrectObjectTypeException, IOException, MissingObjectException {

		Map<SubtreeConfig, ObjectId> resolvedRefs = resolveRefs(
				"refs/heads/original/", branch);

		Map<SubtreeConfig, RevCommit> parentCommits = new LinkedHashMap<SubtreeConfig, RevCommit>();
		RevWalk revWalk = new RevWalk(repository);
		for (SubtreeConfig config : subtreeConfigs) {
			ObjectId objectId = resolvedRefs.get(config);
			if (objectId != null) {
				RevCommit commit = revWalk.parseCommit(objectId);
				parentCommits.put(config, commit);
			}
		}

		String message = createMessage("branch", branch, parentCommits.keySet());

		ObjectId mergeCommit = new SubtreeMerger(repository).createMergeCommit(parentCommits,
				message);

		RefUpdate refUpdate = repository.updateRef("refs/heads/" + branch);
		refUpdate.setNewObjectId(mergeCommit);
		refUpdate.update();
	}

	private void mergeTag(String tagName) throws IOException {
		Map<SubtreeConfig, ObjectId> resolvedRefs = resolveRefs(
				"refs/tags/original/", tagName);

		// Annotated tag that should be used for creating the merged tag, null
		// if only lightweight tags exist
		RevTag referenceTag = null;
		Map<SubtreeConfig, RevCommit> parentCommits = new LinkedHashMap<SubtreeConfig, RevCommit>();

		RevWalk revWalk = new RevWalk(repository);
		try {
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
		} finally {
			revWalk.release();
		}

		String message = createMessage("tag", tagName, parentCommits.keySet());
		ObjectId mergeCommit = new SubtreeMerger(repository).createMergeCommit(parentCommits,
				message);

		ObjectId objectToReference;
		if (referenceTag != null) {
			TagBuilder tagBuilder = new TagBuilder();
			tagBuilder.setTag(tagName);
			tagBuilder.setMessage(referenceTag.getFullMessage());
			tagBuilder.setTagger(referenceTag.getTaggerIdent());
			tagBuilder.setObjectId(mergeCommit, Constants.OBJ_COMMIT);
			ObjectInserter inserter = repository.newObjectInserter();
			try {
				objectToReference = inserter.insert(tagBuilder);
				inserter.flush();
			} finally {
				inserter.release();
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
	}

	private Collection<String> getRefSet(String prefix) throws IOException {
		Map<String, Ref> refs = repository.getRefDatabase().getRefs(prefix);
		TreeSet<String> result = new TreeSet<String>();
		for (String refName : refs.keySet()) {
			String branch = refName.split("/", 2)[1];
			result.add(branch);
		}
		return result;
	}

	private Map<SubtreeConfig, ObjectId> resolveRefs(String refPrefix,
			String name) throws IOException {
		Map<SubtreeConfig, ObjectId> result = new LinkedHashMap<SubtreeConfig, ObjectId>();
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

	private String createMessage(String refType, String refName,
			Set<SubtreeConfig> configsWithRef) {
		LinkedHashSet<SubtreeConfig> configsWithoutRef = new LinkedHashSet<SubtreeConfig>(
				subtreeConfigs);
		configsWithoutRef.removeAll(configsWithRef);

		StringBuilder messageBuilder = new StringBuilder();
		messageBuilder.append("Merge ").append(refType).append(" '").append(refName)
				.append("' from multiple repositories");
		messageBuilder.append("\n\n");
		messageBuilder.append("Repositories:");
		appendRepositoryNames(messageBuilder, configsWithRef);
		if (!configsWithoutRef.isEmpty()) {
			messageBuilder.append("\n\nRepositories without this ").append(refType).append(":");
			appendRepositoryNames(messageBuilder, configsWithoutRef);

			for (SubtreeConfig config : configsWithoutRef) {
				System.err.println("Repository " + config.getRemoteName()
						+ " did not contain " + refType + " '" + refName
						+ "', will not be included in merged " + refType + ".");
			}
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
