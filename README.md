git-merge-repos
===============

Program for merging multiple Git repositories into one, preserving previous
history, tags and branches.

This is useful when you had multiple repositories for one project where you had
more or less the same set of branches and tags.

How it works. For each branch/tag name:

1. All the commits of the repositories that have this branch/tag are collected.
2. The commits are merged with 1 merge commit (with N parents).
3. The branch/tag is recreated on this merge commit. In case of an annotated
   tag, the tag message and tagger information is preserved (this does not work
   for signing information).

Usage
-----

When merging multiple repositories, you can choose between the following two
options:

1. Preserve old commit IDs

   The old history is preserved as-is. Normally, the directory structure of the
   repositories needs to be moved into a subdirectory (one per repository).
   This will be done in the merge commit.

2. Preserve history for paths

   The old history is first rewritten so that all content is within a
   subdirectory. The merge commit does not change the directory structure.

The second option has the advantage that you will still be able to see the
history of a path beyond the merge. With the first option, rename detection may
not be able to cope with the rename in the merge commit.

### Preserve Old Commit IDs

Say you have a repository at `git@example.org:foo.git` and one at
`git@example.org:bar.git`. They both have some common tags and branches.

To merge them, run the program like this:

    ./run.sh git@example.org:foo.git:foodir git@example.org:bar.git:bardir

This will create a merged repository as a subdirectory of the current
directory.

Each tag and branch will be on a new merge commit that merges the commits for
these tags/branches from foo and bar. The merge commits also change the tree
structure so that the contents of foo are in `foodir` and the contents of bar
in `bardir`.

### Preserve History for Paths

With the same example as above, do the following for each repository beforehand
(replace newsubdir with the name you want):

    git clone --mirror git@example.org:repo.git

    cd repo

    git filter-branch --index-filter \
      'tab=$(printf "\t") && git ls-files -s --error-unmatch . >/dev/null 2>&1; [ $? != 0 ] || (git ls-files -s | sed "s-$tab\"*-&newsubdir/-" | GIT_INDEX_FILE=$GIT_INDEX_FILE.new git update-index --index-info && mv "$GIT_INDEX_FILE.new" "$GIT_INDEX_FILE")' \
      --tag-name-filter cat \
      -- --all

Then, run the program like this:

    ./run.sh /absolute/path/to/foo:. /absolute/path/to/bar:.

This time, we don't want the merge commit to change the directory structure, as
we already did that using `git filter-branch`.

Dependencies
------------

You will need to have Java and [Maven][maven] installed. [JGit][jgit] and other
dependencies will be automatically downloaded when running the program.

Other Solutions
---------------

* When you have a simple, linear history, try out
  [git-stitch-repo][git-stitch-repo]
* See the question [Combining multiple git repositories on Stack Overflow][stackoverflow]

License
-------

Copyright © 2013, 2014 Robin Stocker

Licensed under the Apache License, see LICENSE file for details.

[maven]: http://maven.apache.org/
[jgit]: http://eclipse.org/jgit/
[git-stitch-repo]: http://search.cpan.org/~book/Git-FastExport-0.105/script/git-stitch-repo
[stackoverflow]: http://stackoverflow.com/questions/277029/combining-multiple-git-repositories
