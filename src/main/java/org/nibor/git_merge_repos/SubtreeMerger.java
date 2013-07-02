package org.nibor.git_merge_repos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
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
		TreeFormatter treeFormatter = createTreeFormatter(parentCommits, message);
		List<? extends ObjectId> parentIds = new ArrayList<RevCommit>(parentCommits.values());
		ObjectInserter inserter = repository.newObjectInserter();
		try {
			ObjectId treeId = inserter.insert(treeFormatter);

			PersonIdent person = new PersonIdent(repository);
			CommitBuilder commitBuilder = new CommitBuilder();
			commitBuilder.setTreeId(treeId);
			commitBuilder.setAuthor(person);
			commitBuilder.setCommitter(person);
			commitBuilder.setMessage(message);
			commitBuilder.setParentIds(parentIds);
			ObjectId mergeCommit = inserter.insert(commitBuilder);
			inserter.flush();
			return mergeCommit;
		} finally {
			inserter.release();
		}
	}

	private TreeFormatter createTreeFormatter(Map<SubtreeConfig, RevCommit> parentCommits,
			String commitMessage) throws MissingObjectException, IncorrectObjectTypeException,
			CorruptObjectException, IOException {
		TreeWalk treeWalk = new TreeWalk(repository);
		try {
			treeWalk.setRecursive(false);
			addTrees(parentCommits, treeWalk);

			TreeFormatter treeFormatter = new TreeFormatter();
			while (treeWalk.next()) {
				AbstractTreeIterator iterator = getSingleTreeIterator(treeWalk, commitMessage);
				if (iterator == null) {
					throw new IllegalStateException(
							"Tree walker did not return a single tree (should not happen): "
									+ treeWalk.getPathString());
				}
				treeFormatter.append(iterator.getEntryPathBuffer(), 0,
						iterator.getEntryPathLength(), iterator.getEntryFileMode(),
						iterator.getEntryObjectId());
			}
			return treeFormatter;
		} finally {
			treeWalk.release();
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
