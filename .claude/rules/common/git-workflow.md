# Git Workflow

## Commit Message Format
```
<type>: <description>

<optional body>
```

Types: feat, fix, refactor, docs, test, chore, perf, ci

Note: Attribution disabled globally via ~/.claude/settings.json.

## Pull Request Workflow

When creating PRs:
1. Analyze full commit history (not just latest commit)
2. Use `git diff [base-branch]...HEAD` to see all changes
3. Draft comprehensive PR summary
4. Include test plan with TODOs
5. Push with `-u` flag if new branch

> For the full development process (planning, TDD, code review) before git operations,
> see [development-workflow.md](./development-workflow.md).

## Resolving Merge/Rebase Conflicts

1. See the current state of the merge/rebase — check git history and the conflicting files.
2. Find the primary source for each conflict — read the commit messages on both sides, understand deeply why each change was made and what the original intent was.
3. Resolve each hunk by intent — preserve both sides' intent where possible; where incompatible, pick the one matching the merge/rebase's stated goal and note the trade-off explicitly. Never invent new behavior to paper over a conflict.
4. Run the project's automated checks after resolving (Maven/Spotless for backend, ruff/black/mypy for Python, `tsc --noEmit` for frontend) and fix anything the merge broke.
5. **Never `--abort`.** Always resolve and finish the operation — stage everything and commit (or continue the rebase until every commit is rebased). If truly stuck, ask the user before aborting; don't default to it.
