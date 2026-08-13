# Taskboard: Purge obsolete prompt & scratch files

| Task ID | Description | Priority | Status | Owner | Verification |
| --- | --- | --- | --- | --- | --- |
| T1 | Identify all obsolete prompt txt/md and scratch files in repository | P0 | COMPLETED | oma-executor | Identified prompt.txt, new_pp_prompt.md, new_prompt.txt, PLAN.md, ORIGINAL_REQUEST.md, PROJECT.md |
| T2 | Remove obsolete files from git index and filesystem | P0 | COMPLETED | oma-executor | git rm executed for tracked files and deleted unneeded scratch files |
| T3 | Run validation gate (check_translations) | P0 | COMPLETED | oma-verifier | check_translations PASS |
| T4 | Commit and push cleanup to GitHub origin main | P0 | IN_PROGRESS | oma-executor | Ready for commit and push |
