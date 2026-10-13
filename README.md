# elf-runner

The [House Elf](https://github.com/House-elves) that runs [Elf Desk](../elf-desk)'s agent jobs on this machine.

It dials out to the desk over a WebSocket (nothing connects in) and authenticates with a shared token. For each job it:

1. runs a quick safety check on any issue or PR text written by people outside the repo, and refuses the job if that text tries to instruct an AI;
2. prepares a git worktree for the issue or PR: one clone per repo, one worktree per item, with PR heads fetched from `pull/N/head`;
3. runs Claude Code headless (`claude -p --output-format stream-json --json-schema …`), resuming the thread's session for follow-ups;
4. streams what the agent is doing back to the desk, then returns its structured answer (a reply, plus an optional draft review or comment).

It also carries out the desk's GitHub writes. When you confirm a post or a close in the desk, the desk sends the exact request (`github.write`), and the runner process sends it with `gh auth token -u $GITHUB_USER`. That's the same "GitHub CLI" app Claude Code uses, so orgs that restrict OAuth Apps accept it. Only these requests are accepted: post a review (`POST /repos/o/r/pulls/N/reviews`), post a comment (`POST /repos/o/r/issues/N/comments`), open an issue (`POST /repos/o/r/issues` with exactly `title` and `body`; the desk checks the repo is public first), close or reopen (`PATCH /repos/o/r/issues/N` with exactly `{"state":"closed"}` or `{"state":"open"}`; reopening is how the desk re-runs CI on your own PR in a repo where you can't re-run workflows), merge (`PUT /repos/o/r/pulls/N/merge` with exactly `merge_method` and a 40-character `sha`, so a merge can't pick up commits pushed after you clicked), re-run failed jobs (`POST /repos/o/r/actions/runs/N/rerun-failed-jobs`, empty body), approve a fork PR's waiting workflow runs (`POST /repos/o/r/actions/runs/N/approve`, empty body), open a PR from your own fork (`POST /repos/o/r/pulls` with exactly `title`, `body`, `base`, a boolean `draft` and a `head` of `$GITHUB_USER:branch`), mark your own draft PR ready for review (`github.ready`, refused for anyone else's PR), and sync your own fork's branch with its upstream (`POST /repos/$GITHUB_USER/r/merge-upstream` with exactly `branch`). For an issue, the agent's commits are squashed into one on the default branch when its job ends, ready to become a PR.

Every item (issue or PR) has its own clone, `worktrees/<owner>_<repo>_<number>`, which only that item's agent can write, and an item runs one job, push or squash at a time. The clones borrow their objects from a cache per repo, `cache/<owner>/<repo>.git` (bare, `blob:none`), which only the runner writes and the sandbox mounts read-only, so nothing one agent does reaches another item. The runner still runs git in an item's clone itself (fetch, checkout, diff, push with your token), so before each use it resets everything git would run or obey there: the config, hooks, attributes, grafts, per-worktree config and alternates (only the cache). A clone whose `.git` isn't a real directory is removed and the action refused. Diffs for the desk are read as plain text (no textconv, external diff or `-diff` attribute) and capped at 400 KB. PR heads (the lease for squashed pushes) live in `heads/`, outside the sandbox. Each sandbox mounts only its own item's clone (and the cache, read-only) and gets its own view of the Claude config dir: the shared login, its own sessions and a throwaway copy of `.claude.json`, with CLAUDE.md, settings, agents, commands, skills and plugins read-only; everything else Claude writes is gone when the run ends. Two items whose paths Claude would map to the same session dir are refused. Still shared between agents: `~/.m2` and, with `SANDBOX_PODMAN=true`, your Podman socket (see Podman above). The prompt-injection check runs in its own directory under `triage/`, which no agent can reach. Before reading an agent's commits, the runner resets `origin/*` from the cache and ignores replace objects, and a push must start from the PR's real head or a commit of the default branch, so the diff you review is what gets pushed.

It also updates a PR that's behind its base (`github.updateBranch`): one GraphQL mutation, `updatePullRequestBranch`, built by the runner with only the PR, the expected head commit and REBASE or MERGE, so GitHub does the update and refuses it if the PR moved.

It also starts mvnpm-dependabot when you ask from the desk (`mvnpm.run`): `systemctl --user start mvnpm-dependabot.service` for a full round (the elf's own unit), or a one-off `systemd-run` unit `mvnpm-dependabot-pr-<owner>-<repo>-<n>` running `mvnpm-dependabot --repo R --pr N`. It reports which checks are running (`mvnpm.state`) until they finish, so the desk can show progress and refresh. The elf does its own commenting and approving, as before.

It runs daily-triage when you ask from the desk (`triage.run`), in one of two fixed modes and with nothing else passed: `email` (`daily-triage --triage-only --no-email`: the email triage, saved for the desk instead of emailed) and `zulip` (`daily-triage --zulip-only`: the last day of Zulip with a digest per topic, marking nothing read). Each runs as a `systemd-run` unit `daily-triage-desk-<mode>`; the runner reports which run and, for a failed one, the end of its log (`triage.state`). The desk reads the saved runs from `~/.config/daily-triage/runs`.

It also pushes the agent's commits when you confirm a push in the desk (`git.push`). It pushes the PR's worktree HEAD to exactly the branch the desk names. The worktree must be at the commit you saw, that commit must descend from the branch's head when the agent started, and the push is plain fast-forward, so if the branch moved on GitHub nothing is pushed. The token reaches git through a credential helper for that one command. The agent only commits locally (`git commit`); it never pushes. The token is read fresh for each write, and never stored, logged, or passed to the agent.

## The sandbox

Agents run inside [bubblewrap](https://github.com/containers/bubblewrap). Removing tokens from the environment isn't enough: any process running as your user can read your desktop keyring over the session bus, and `gh auth token -u <account>` reads it directly. Inside the sandbox the agent sees:

| Visible | Hidden |
|---|---|
| `/usr` and `/etc` (read-only) | The session bus, so no keyring |
| An empty `$HOME` containing only: `~/.sdkman` (read-only), a private `~/.m2`, the Claude binary (read-only) | `~/.config` (gh, and the other elves' tokens and app passwords) |
| The runner's clones, worktrees and temp dir | `~/.ssh`, the ssh-agent socket, `~/.gnupg` |
| The runner's own Claude config dir | Your `~/.claude` (other sessions' transcripts) |
| The network | The runner's config, the desk's `.env` |

`gh` sees only a placeholder `GH_TOKEN`. git uses a global config with no credential helper, `GIT_SSH_COMMAND=false`, and anonymous HTTPS remotes. The agent can read public GitHub and commit locally, but it cannot push, comment or merge.

To look around inside the sandbox yourself:

```shell
jbang ElfRunner.java --sandbox-shell bash
```

**Podman.** With `SANDBOX_PODMAN=true`, your rootless Podman socket is bound into the sandbox. `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` point at it, and a `containers.conf` makes the `podman` CLI a client of it. That's enough for Quarkus Dev Services and Testcontainers (checked: Ryuk and Postgres start, and tests pass) and for `podman run`. The trade-off: a container can bind-mount anything your user can, including the session bus, so an agent that set out to could reach the keyring that way. It's off by default.

## Setup

1. **Config.** Create `~/.config/elf-runner/config` (mode 600):

   ```
   DESK_URL=ws://localhost:7777/runner
   RUNNER_TOKEN=<same value as ELFDESK_RUNNER_TOKEN in the desk's .env>
   ```

2. **Claude login, once.** The sandboxed agent uses its own Claude config dir, so it can't read your other sessions. In a terminal:

   ```shell
   CLAUDE_CONFIG_DIR=~/.local/share/elf-runner/claude claude
   ```

   Run `/login`, then `/exit`. Optionally add a `CLAUDE.md` in that directory with instructions for the agent.

3. **Run.** As a service (what the desk uses):

   ```shell
   ./deploy.sh            # build the jar, install the elf-runner.service unit, restart (refuses while an agent runs)
   journalctl --user -u elf-runner -f
   ```

   It runs `~/.local/share/elf-runner/bin/elf-runner.jar`, so editing `ElfRunner.java` changes nothing until you deploy. To try a change by hand: `jbang ElfRunner.java` (stop the service first).

## Configuration

| Key | Default | |
|---|---|---|
| `DESK_URL` | `ws://localhost:7777/runner` | |
| `RUNNER_TOKEN` | required | Shared with the desk |
| `WORK_DIR` | `~/.local/share/elf-runner` | Clones, worktrees, private `.m2`, Claude config |
| `MAX_JOBS` | `2` | Jobs at once; more wait for a slot |
| `JOB_TIMEOUT_MINUTES` | `45` | |
| `MODEL` | Claude Code's default | |
| `TRIAGE_MODEL` | `claude-haiku-4-5-20251001` | For the safety check |
| `MCP_CONFIG` | none | A Claude Code MCP config file for the agent (only these servers load) |
| `SANDBOX` | `bwrap` | `none` runs the agent unsandboxed, where it **can** reach your credentials |
| `SANDBOX_PODMAN` | `false` | Podman socket in the sandbox (see above) |
| `CANARY` | none | A fixed opening word your `CLAUDE.md` asks for, stripped from answers and drafts |
| `GIT_NAME` / `GIT_EMAIL` | `Elf Runner` | Identity for the agent's commits, which you may push to a PR. Set it to `GITHUB_USER`'s identity. It deliberately doesn't fall back to your global git config, which may be another account's |
| `GITHUB_USER` | required for posting | The `gh` account the desk's posts and closes go out as, e.g. `phillip-kruger`. Asked for by name, so your active `gh` account doesn't matter |

Agent stderr goes to `WORK_DIR/agent-stderr.log`.
