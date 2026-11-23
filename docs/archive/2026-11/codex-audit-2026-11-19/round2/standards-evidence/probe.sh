#!/usr/bin/env bash
set -euo pipefail
evidence="$(cd "$(dirname "$0")" && pwd -P)"
root="$(cd "$1" && pwd -P)"
repo="$root/repo"
real_git="$(command -v git)"
export real_git
mkdir -p "$repo" "$root/bin" "$root/restore"
"$real_git" -C "$repo" init -q -b main
"$real_git" -C "$repo" config user.name Fixture
"$real_git" -C "$repo" config user.email fixture@example.invalid
printf 'base\n' > "$repo/file.txt"
"$real_git" -C "$repo" add file.txt
"$real_git" -C "$repo" commit -qm base
"$real_git" -C "$repo" update-ref refs/remotes/origin/main HEAD
"$real_git" -C "$repo" worktree add -qb fixture-case "$root/target"
printf 'tracked edits\n' > "$root/target/file.txt"
printf 'local draft\n' > "$root/target/untracked.txt"
cat > "$root/bin/git" <<'SHIM'
#!/usr/bin/env bash
case " $* " in
  *" worktree remove "*)
    printf 'remove requested, intercepted before execution\n' >> "$remove_log"
    exit 0 ;;
esac
if [ "$fixture_mode" = "status" ]; then
  case " $* " in *" status --porcelain "*) exit 77;; esac
fi
if [ "$fixture_mode" = "list" ]; then
  case " $* " in *" ls-files --others --exclude-standard "*) exit 78;; esac
fi
if [ "$fixture_mode" = "race" ]; then
  case " $* " in
    *" diff --binary HEAD "*)
      "$real_git" "$@" || exit "$?"
      printf 'edited while archiving\n' > "$fixture_target/file.txt"
      exit 0 ;;
  esac
fi
if [ "$fixture_mode" = "corruption" ]; then
  case " $* " in
    *" bundle create "*)
      "$real_git" "$@" || exit "$?"
      for bundle; do
        case "$bundle" in *.bundle) truncate -s -16 "$bundle";; esac
      done
      exit 0 ;;
  esac
fi
exec "$real_git" "$@"
SHIM
chmod u+x "$root/bin/git"
export PATH="$root/bin:$PATH"
export IRIS_NO_PR_LOOKUP=1
export fixture_target="$root/target"
cd "$repo"
run() {
  export fixture_mode="$1"
  export remove_log="$root/$1-remove.log"
  export IRIS_ARCHIVE_BASE="$root/$1-archive"
  status=0
  bash "$evidence/candidate-hygiene.sh" reap --apply --archive-dirty --drop-unique --older-than 0 > "$root/$1-stdout.log" 2>&1 || status=$?
  printf '%s exit=%s intercepted_remove_calls=%s\n' "$1" "$status" "$(if [ -f "$remove_log" ]; then wc -l < "$remove_log"; else echo 0; fi)"
}
run status
run list
printf 'list_tar_files=%s\n' "$(find "$root/list-archive" -name 'untracked.tar.gz' | wc -l)"
run race
printf 'archived_before_edit=%s current_contains_after_edit=%s\n' "$(grep -l 'tracked edits' "$root"/race-archive/*/changes.patch | wc -l)" "$(grep -l 'edited while archiving' "$root/target/file.txt" | wc -l)"
"$real_git" -C "$root/target" add file.txt
"$real_git" -C "$root/target" commit -qm unique
run positive
bundle="$(find "$root/positive-archive" -name 'history.bundle' -print -quit)"
"$real_git" -C "$root/restore" init -q
"$real_git" -C "$root/restore" bundle unbundle "$bundle" > "$root/positive-restore.log" 2>&1
printf 'valid_bundle_restore_exit=0 valid_bundle_restored_HEAD=%s\n' "$("$real_git" -C "$root/restore" cat-file -t "$("$real_git" -C "$root/target" rev-parse HEAD)")"
run corruption
bundle="$(find "$root/corruption-archive" -name 'history.bundle' -print -quit)"
mkdir "$root/corrupt-restore"
"$real_git" -C "$root/corrupt-restore" init -q
restore=0
"$real_git" -C "$root/corrupt-restore" bundle unbundle "$bundle" > "$root/corruption-restore.log" 2>&1 || restore=$?
printf 'corrupt_bundle_restore_exit=%s\n' "$restore"
test -e "$root/target/file.txt"
test -e "$root/target/untracked.txt"
