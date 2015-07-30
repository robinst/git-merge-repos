package org.nibor.git_merge_repos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.RawParseUtils;

/**
 * Merges the passed commit trees into one tree, adjusting directory structure
 * if necessary (depends on options from user).
 */
public class SubtreeMerger {

	private final Repository repository;

	public SubtreeMerger(Repository repository) {
		this.repository = repository;
	}

	public ObjectId createMergeCommit(Map<SubtreeConfig, RevCommit> parentCommits, String message)
			throws IOException {
		PersonIdent latestIdent = getLatestPersonIdent(parentCommits.values());
		DirCache treeDirCache = createTreeDirCache(parentCommits, message);
		List<? extends ObjectId> parentIds = new ArrayList<>(parentCommits.values());
		try (ObjectInserter inserter = repository.newObjectInserter()) {
			ObjectId treeId = treeDirCache.writeTree(inserter);

			PersonIdent repositoryUser = new PersonIdent(repository);
			PersonIdent ident = new PersonIdent(repositoryUser, latestIdent.getWhen().getTime(),
					latestIdent.getTimeZoneOffset());
			CommitBuilder commitBuilder = new CommitBuilder();
			commitBuilder.setTreeId(treeId);
			commitBuilder.setAuthor(ident);
			commitBuilder.setCommitter(ident);
			commitBuilder.setMessage(message);
			commitBuilder.setParentIds(parentIds);
			ObjectId mergeCommit = inserter.insert(commitBuilder);
			inserter.flush();
			return mergeCommit;
		}
	}

	private PersonIdent getLatestPersonIdent(Collection<RevCommit> commits) {
		PersonIdent latest = null;
		for (RevCommit commit : commits) {
			PersonIdent ident = commit.getCommitterIdent();
			Date when = ident.getWhen();
			if (latest == null || when.after(latest.getWhen())) {
				latest = ident;
			}
		}
		return latest;
	}

	private DirCache createTreeDirCache(Map<SubtreeConfig, RevCommit> parentCommits,
			String commitMessage) throws IOException {

		try (TreeWalk treeWalk = new TreeWalk(repository)) {
			treeWalk.setRecursive(true);
			addTrees(parentCommits, treeWalk);

			DirCacheBuilder builder = DirCache.newInCore().builder();
			while (treeWalk.next()) {
				AbstractTreeIterator iterator = getSingleTreeIterator(treeWalk, commitMessage);
				if (iterator == null) {
					throw new IllegalStateException(
							"Tree walker did not return a single tree (should not happen): "
									+ treeWalk.getPathString());
				}
				byte[] path = Arrays.copyOf(iterator.getEntryPathBuffer(),
						iterator.getEntryPathLength());
				DirCacheEntry entry = new DirCacheEntry(path);
				entry.setFileMode(iterator.getEntryFileMode());
				entry.setObjectId(iterator.getEntryObjectId());
				builder.add(entry);
			}
			builder.finish();
			return builder.getDirCache();
		}
	}

	private void addTrees(Map<SubtreeConfig, RevCommit> parentCommits, TreeWalk treeWalk)
			throws IOException {
		for (Map.Entry<SubtreeConfig, RevCommit> entry : parentCommits.entrySet()) {
			String directory = entry.getKey().getSubtreeDirectory();
			RevCommit parentCommit = entry.getValue();
			if (".".equals(directory)) {
				treeWalk.addTree(parentCommit.getTree());
			} else {
				byte[] prefix = directory.getBytes(RawParseUtils.UTF8_CHARSET);
				CanonicalTreeParser treeParser = new CanonicalTreeParser(prefix,
						treeWalk.getObjectReader(), parentCommit.getTree());
				treeWalk.addTree(treeParser);
			}
		}
	}

	private AbstractTreeIterator getSingleTreeIterator(TreeWalk treeWalk, String commitMessage) {
		AbstractTreeIterator result = null;
		int treeCount = treeWalk.getTreeCount();
		for (int i = 0; i < treeCount; i++) {
			AbstractTreeIterator it = treeWalk.getTree(i, AbstractTreeIterator.class);
			if (it != null) {
				if (result != null) {
					String msg = "Trees of repositories overlap in path '"
							+ it.getEntryPathString()
							+ "'. "
							+ "We can only merge non-overlapping trees, "
							+ "so make sure the repositories have been prepared for that. "
							+ "One possible way is to process each repository to move the root to a subdirectory first.\n"
							+ "Current commit:\n" + commitMessage;
					throw new IllegalStateException(msg);
				} else {
					result = it;
				}
			}
		}
		return result;
	}
}
