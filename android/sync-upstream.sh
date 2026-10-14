#!/usr/bin/env bash
# Brings the PC port's latest changes (github.com/FelipePrado0/gow3_pc, branch main) into this
# Android fork: a plain `git merge`, so history stays shared and every later sync only merges what is
# new. The Android work lives in new paths (android/, cpu/, third_party/FEX) plus a few small, marked
# edits to shared files, so most syncs merge without conflicts.
#
#   android/sync-upstream.sh            fetch and merge upstream/main into the current branch
#   android/sync-upstream.sh --check    fetch and report what would change, without merging
#
# After a merge, check the Android build still compiles: android/scripts/check.sh (fast) or the
# full android/scripts/build_arm64.sh. On a conflict the merge stops for you to resolve it; the shared
# files this fork changes are listed below, and `git merge --abort` undoes everything.
set -euo pipefail
cd -- "$(git -C "$(dirname -- "$0")" rev-parse --show-toplevel)"
url=${GOW3_UPSTREAM_URL:-https://github.com/FelipePrado0/gow3_pc.git}
branch=${GOW3_UPSTREAM_BRANCH:-main}
if ! git remote get-url upstream >/dev/null 2>&1; then
    git remote add upstream "$url"
    echo "Added remote upstream: $url"
fi
git fetch --quiet upstream "$branch"
target=upstream/$branch
new=$(git rev-list --count "HEAD..$target")
if [[ $new == 0 ]]; then
    echo "Up to date with $target."
    exit 0
fi
echo "$new new upstream commit(s) on $target:"
git log --oneline --no-merges "HEAD..$target" | head -40
base=$(git merge-base HEAD "$target")
# Shared files both sides changed since the last sync: the only places a conflict can happen.
mapfile -t ours < <(git diff --name-only "$base" HEAD)
mapfile -t theirs < <(git diff --name-only "$base" "$target")
overlap=$(comm -12 <(printf '%s\n' "${ours[@]}" | sort) <(printf '%s\n' "${theirs[@]}" | sort) || true)
if [[ -n $overlap ]]; then
    echo
    echo 'Files changed on both sides (check these after the merge):'
    printf '  %s\n' $overlap
fi
if [[ ${1:-} == --check ]]; then
    exit 0
fi
if [[ -n $(git status --porcelain --untracked-files=no) ]]; then
    echo 'Commit or stash your local changes first.' >&2
    exit 1
fi
echo
if git merge --no-edit -m "Merge upstream gow3_pc ($branch) into the Android fork" "$target"; then
    git submodule update --init --recursive --quiet || true
    echo
    echo "Merged. Next: android/scripts/check.sh, then push."
else
    echo
    echo 'Conflicts. Resolve them (keep both the upstream change and the Android lines, marked'
    echo '"Android" or guarded by GUEST_CPU_NATIVE / __aarch64__), then `git commit`. `git merge --abort` undoes it.'
    exit 1
fi
