#!/usr/bin/env bash
# From the pinned RC checkout, invoke with Git Bash. This harness runs only the
# archive function with synthetic git output and never invokes a removal path.
set -uo pipefail
p="disposable-worktree"
ARCHIVE_BASE="$(mktemp -d)"
trap 'rm -f "$ARCHIVE_BASE"/disposable-worktree.{patch,meta.txt}; rmdir "$ARCHIVE_BASE"' EXIT
git() {
  case "$3" in
    diff) printf "tracked fixture\n";;
    rev-parse) printf "synthetic-ref\n";;
    status) printf "?? synthetic-untracked\n";;
    ls-files) printf "synthetic-untracked\n";;
  esac
}
tar() { echo "synthetic archive write failure" >&2; return 73; }
source <(sed -n '79,99p' scripts/worktree-hygiene.sh)
archive_worktree "$p"
archive_status=$?
printf 'archive_status=%s\n' "$archive_status"
test "$archive_status" -ne 0
