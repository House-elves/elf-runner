<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# elf-runner

The [House Elf](https://github.com/House-elves) that runs [Elf Desk](https://github.com/House-elves/elf-desk)'s agent jobs on this machine.

It dials out to the desk over a WebSocket (nothing connects in) and authenticates with a shared token. For each job it:

1. runs a quick safety check on any issue or PR text written by people outside the repo, and refuses the job if that text tries to instruct an AI;
2. prepares a git worktree for the issue or PR: one clone per repo, one worktree per item, with PR heads fetched from `pull/N/head`;
3. runs Claude Code headless (`claude -p --output-format stream-json --json-schema …`), resuming the thread's session for follow-ups;
4. streams what the agent is doing back to the desk, then returns its structured answer (a reply, plus an optional draft review or comment).

It also carries out the desk's GitHub writes. When you confirm a post or a close in the desk, the desk sends the exact request (`github.write`), and the runner process sends it with `gh auth token -u $GITHUB_USER`. That's the same "GitHub CLI" app Claude Code uses, so orgs that restrict OAuth Apps accept it. Only these requests are accepted: post a review (`POST /repos/o/r/pulls/N/reviews`), post a comment (`POST /repos/o/r/issues/N/comments`), open an issue (`POST /repos/o/r/issues` with exactly `title` and `body`; the desk checks the repo is public first), close or reopen (`PATCH /repos/o/r/issues/N` with exactly `{"state":"closed"}` or `{"state":"open"}`; reopening is how the desk re-runs CI on your own PR in a repo where you can't re-run workflows), merge (`PUT /repos/o/r/pulls/N/merge` with exactly `merge_method` and a 40-character `sha`, so a merge can't pick up commits pushed after you clicked), re-run failed jobs (`POST /repos/o/r/actions/runs/N/rerun-failed-jobs`, empty body), approve a fork PR's waiting workflow runs (`POST /repos/o/r/actions/runs/N/approve`, empty body), open a PR from your own fork (`POST /repos/o/r/pulls` with exactly `title`, `body`, `base`, a boolean `draft` and a `head` of `$GITHUB_USER:branch`), mark your own draft PR ready for review (`github.ready`, refused for anyone else's PR), and sync your own fork's branch with its upstream (`POST /repos/$GITHUB_USER/r/merge-upstream` with exactly `branch`). For an issue, the agent's commits are squashed into one on the default branch when its job ends, ready to become a PR.

Every item (issue or PR) has its own clone, `worktrees/<owner>_<repo>_<number>`, which only that item's agent can write, and an item runs one job, push or squash at a time. The clones borrow their objects from a cache per repo, `cache/<owner>/<repo>.git` (bare, `blob:none`), which only the runner writes and the sandbox mounts read-only, so nothing one agent does reaches another item. The runner still runs git in an item's clone itself (fetch, checkout, diff, push with your token), so before each use it resets everything git would run or obey there: the config, hooks, attributes, grafts, per-worktree config and alternates (only the cache). A clone whose `.git` isn't a real directory is removed and the action refused. Diffs for the desk are read as plain text (no textconv, external diff or `-diff` attribute) and capped at 400 KB. PR heads (the lease for squashed pushes) live in `heads/`, outside the sandbox. Each sandbox mounts only its own item's clone (and the cache, read-only) and gets its own view of the Claude config dir: the shared login, its own sessions and a throwaway copy of `.claude.json`, with CLAUDE.md, settings, agents, commands, skills and plugins read-only; everything else Claude writes is gone when the run ends. Two items whose paths Claude would map to the same session dir are refused. Still shared between agents: `~/.m2` and, with `SANDBOX_PODMAN=true`, your Podman socket (see Podman above). The prompt-injection check runs in its own directory under `triage/`, which no agent can reach. Before reading an agent's commits, the runner resets `origin/*` from the cache and ignores replace objects, and a push must start from the PR's real head or a commit of the default branch, so the diff you review is what gets pushed.

It also updates a PR that's behind its base (`github.updateBranch`): one GraphQL mutation, `updatePullRequestBranch`, built by the runner with only the PR, the expected head commit and REBASE or MERGE, so GitHub does the update and refuses it if the PR moved.

It also starts mvnpm-dependabot when you ask from the desk (`mvnpm.run`, Linux only for now): a full round as `mvnpm-dependabot.service` (the elf's own unit if it has one, else a `systemd-run` unit running `--once`), or a one-off `systemd-run` unit `mvnpm-dependabot-pr-<owner>-<repo>-<n>` running `--repo R --pr N`. It reports which checks are running (`mvnpm.state`) until they finish, so the desk can show progress and refresh. The elf does its own commenting and approving, as before.

### Elves

It installs and runs elves for the desk (its + page, and each elf's settings). An elf is a repo with an `elf.yaml` (see [weekly-status's](https://github.com/House-elves/weekly-status/blob/main/elf.yaml)); its name and title, its version and author (`version: 1.0.0`, `author: "@login"`, shown by the desk), settings, commands, its tab and how it refreshes, what it reads and writes, and whether it starts agents (`agents: {done: <action>}`).

- `elf.install` clones the repo into `~/.local/share/house-elves/elves/<name>` and checks out the given commit, `elf.changes` shows the commits and diff to a newer one, `elf.update` moves to it, and `elf.remove` deletes the clone (its settings stay). The clone never moves otherwise, so only code you looked at runs.
- The runner installs only elves from House-elves or your own account (`GITHUB_USER`), whatever the desk asks, and a name stays with the repo it first came from, so another repo never inherits an elf's settings. A setting that was ever secret (or whose key has PASSWORD, API_KEY, TOKEN or SECRET in it) stays secret, with what it goes with, whatever a later `elf.yaml` says, and a version that leaves out a remembered secret or what it goes with isn't installed.
- Every `elf.yaml` is checked: the config file must be the elf's own `~/.config/<name>/config`, House Elves' names are taken, arguments are plain `--options`, and setting kinds are the ones the desk shows. The desk's settings for an elf are the ones its `elf.yaml` lists (plus the runner's own two).
- `elf.run` runs one of the commands the elf declares in the background, with its fixed arguments, plus its params as `--name=value` once each value matches the `elf.yaml`'s pattern. Its output goes to `~/.local/share/house-elves/elf-logs/<elf>:<command>.log`, and `elves.state` says which runs are going and ends a failed run's log. The elf runs as you, outside the sandbox (it holds its own passwords and API keys), with `jbang` on its main file.
- For an elf's own tab on the desk: `elf.file` reads a file of its UI (only under `ui/`, no links leading out), `elf.data` and `elf.read` list and read the files in the folders its `elf.yaml` lists under `data` (in `~/.config/<name>/`, never its config file), and `elf.action` runs one of its `actions`: the fixed arguments, with the values (each checked against the `elf.yaml`: text with a pattern and a length, one of a list, an email, numbers) as JSON on its stdin.
- Refreshing: an elf's `refresh` names the command that brings its tab up to date. Once a minute the runner runs it for an elf whose refresh time has passed since its last scheduled run, so a run missed while asleep happens once on waking. No systemd timers, so it's the same on macOS. The time is part of systemd's calendar format: optional weekdays, `*-*-*`, then the hours (one, a list, a range or `*`) and the minute, several joined with `;` (`Mon..Fri *-*-* 07:00`, `Mon..Fri *-*-* 08..18:00`, `*-*-* 07:00; Mon *-*-* 13:30`). The `elf.yaml` suggests one; `elf.schedule` sets yours (in `elf-state/<name>.when`, `off` for never), or goes back to the elf's own.
- First refresh: once every setting an elf can't run without has a value (no default, not `optional`), the runner starts its refresh by itself: when it's installed with its settings already there, or when the desk saves the last one. Only the first time: not once that refresh ran, nor for an elf with files in its `data` folders already. `elves.list` says which settings are still `missing`.

It also pushes the agent's commits when you confirm a push in the desk (`git.push`). It pushes the PR's worktree HEAD to exactly the branch the desk names. The worktree must be at the commit you saw, that commit must descend from the branch's head when the agent started, and the push is plain fast-forward, so if the branch moved on GitHub nothing is pushed. The token reaches git through a credential helper for that one command. The agent only commits locally (`git commit`); it never pushes. The token is read fresh for each write, and never stored, logged, or passed to the agent.

## The sandbox

Agents run inside [bubblewrap](https://github.com/containers/bubblewrap) on Linux, and `sandbox-exec` on macOS (see below). Removing tokens from the environment isn't enough: any process running as your user can read your desktop keyring over the session bus, and `gh auth token -u <account>` reads it directly. Inside the sandbox the agent sees:

| Visible | Hidden |
|---|---|
| `/usr` and `/etc` (read-only) | The session bus, so no keyring |
| An empty `$HOME` containing only: the JDK, Maven and Claude Code folders (read-only, wherever they're installed), a private `~/.m2` | `~/.config` (gh, and the other elves' tokens and app passwords) |
| The runner's clones, worktrees and temp dir | `~/.ssh`, the ssh-agent socket, `~/.gnupg` |
| The runner's own Claude config dir | Your `~/.claude` (other sessions' transcripts) |
| The network | The runner's config, the desk's config and data |

`gh` sees only a placeholder `GH_TOKEN`. git uses a global config with no credential helper, `GIT_SSH_COMMAND=false`, and anonymous HTTPS remotes. The agent can read public GitHub and commit locally, but it cannot push, comment or merge.

To look around inside the sandbox yourself:

```shell
jbang ElfRunner.java --sandbox-shell bash
```

**macOS.** There's no bubblewrap, so the agent runs under `sandbox-exec` with a deny-by-default profile: it may read the system, the tool folders and its own folders, and write only its item's clone, its own home, temp and Claude config (all per run) and the shared `~/.m2`. It can't reach your real home, the Keychain, the clipboard, LaunchServices (opening apps), Apple Events, launchd or the shared `/tmp`. Java and Maven are pointed at the agent's home (`-Duser.home`), since Java ignores `$HOME` there. Claude Code keeps its login in the Keychain on macOS, so the agent needs a token (`CLAUDE_CODE_OAUTH_TOKEN`). `test/sandbox-probe.sh` checks all of this, and CI runs it on Linux and macOS.

**Podman.** With `SANDBOX_PODMAN=true`, your rootless Podman socket is bound into the sandbox. `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` point at it, and a `containers.conf` makes the `podman` CLI a client of it. That's enough for Quarkus Dev Services and Testcontainers (checked: Ryuk and Postgres start, and tests pass) and for `podman run`. The trade-off: a container can bind-mount anything your user can, including the session bus, so an agent that set out to could reach the keyring that way. It's off by default.

## Setup

[house-elves](https://github.com/House-elves/house-elves) installs and configures the runner with the desk: `house-elves install`. It writes `~/.config/house-elves/runner.config` (mode 600) with the desk's address and token, your `gh` account, the identity for the agent's commits, and a Claude token for the agent from `claude setup-token` (your Claude subscription, not API billing). The agent's own Claude config dir is `WORK_DIR/claude`, so it can't read your other sessions; add a `CLAUDE.md` there for instructions to the agent.

It runs the installed jar (`~/.local/share/house-elves/app/elf-runner.jar`) as a user service, so editing `ElfRunner.java` changes nothing until you deploy:

```shell
./deploy.sh              # build the jar, swap it in, restart (refuses while an agent runs)
house-elves logs runner
```

To try a change by hand: `jbang ElfRunner.java` (stop the service first). An older setup's `~/.config/elf-runner/config` still works when there's no `runner.config`.

## Configuration

| Key | Default | |
|---|---|---|
| `DESK_URL` | `ws://localhost:7777/runner` | |
| `CLAUDE_CODE_OAUTH_TOKEN` | none (required on macOS) | The agent's Claude login, from `claude setup-token`. Without it, on Linux, the agent uses a login in `WORK_DIR/claude` |
| `RUNNER_TOKEN` | required | Shared with the desk |
| `WORK_DIR` | `~/.local/share/house-elves/runner` (`~/.local/share/elf-runner` with the old config file) | Clones, worktrees, private `.m2`, Claude config |
| `MAX_JOBS` | `2` | Jobs at once; more wait for a slot |
| `JOB_TIMEOUT_MINUTES` | `45` | |
| `MODEL` | Claude Code's default | |
| `TRIAGE_MODEL` | `claude-haiku-4-5-20251001` | For the safety check |
| `ASK_AGENT` | `claude` | The coding agent elves ask (`claude`, `codex`, `pi` or `bob`), unless an elf's own settings choose another. See below |
| `BOB_API_KEY` / `BOB_TEAM_ID` | none | IBM Bob's API key for headless runs (and the team ID a general key needs), only if elves ask Bob. Given to Bob alone |
| `MCP_CONFIG` | none | A Claude Code MCP config file for the agent (only these servers load) |
| `SANDBOX` | `bwrap` (`sandbox-exec` on macOS) | `none` runs the agent unsandboxed, where it **can** reach your credentials |
| `SANDBOX_PODMAN` | `false` | Podman socket in the sandbox (see above) |
| `CANARY` | none | A fixed opening word your `CLAUDE.md` asks for, stripped from answers and drafts |
| `GIT_NAME` / `GIT_EMAIL` | `Elf Runner` | Identity for the agent's commits, which you may push to a PR. Set it to `GITHUB_USER`'s identity. It deliberately doesn't fall back to your global git config, which may be another account's |
| `GITHUB_USER` | required for posting | The `gh` account the desk's posts and closes go out as, e.g. `phillip-kruger`. Asked for by name, so your active `gh` account doesn't matter |

Agent stderr goes to `WORK_DIR/agent-stderr.log`.

## Asking a coding agent (ELF_ASK)

Elves that sum up, sort or suggest replies (`ai: [ask]` in their `elf.yaml`) ask a coding agent through the runner:
every elf it starts gets `ELF_ASK`, the path of `bin/elf-ask` in the data folder (the runner writes it when it
starts), and `ELF_ASK_AGENT`, the agent to ask. That is the elf's own **Coding agent** setting, or `ASK_AGENT` from
the runner's settings, or Claude Code. Each agent is asked with nothing it could act with, because what it reads
includes other people's words:

| Agent | Run as |
|---|---|
| Claude Code | `claude -p --model sonnet --tools "" --strict-mcp-config --setting-sources ""` |
| Codex | `codex exec --ephemeral --skip-git-repo-check --ignore-user-config --ignore-rules --sandbox read-only`, with web search and its tool features (shell, apps, plugins, browser, images, hooks, …) switched off |
| pi | `pi -p --no-tools --no-extensions --no-mcp --no-skills --no-prompt-templates --no-themes --no-context-files --no-session --no-approve` |
| IBM Bob | `bob run -f json --mode ask --disable-mcp --disable-subagents --disable-tool-groups read,edit,execute,browser,mcp,skill,todo,subagent,mode --max-turns 3`, with `BOB_API_KEY` from the runner's settings |

each in an empty folder of its own, with the agent's own login. `elf-ask` reads the prompt on stdin, takes
`--schema JSON`, `--system TEXT` and `--minutes N`, and answers as `claude -p --output-format json` does (`result`,
`structured_output`, `total_cost_usd`, `is_error`), whichever agent it asked. Only Claude Code says what it cost in dollars (Bob counts in Bobcoins). Bob's licence is yours to accept (run `bob` once);
`elf-ask` never accepts it for you.

`elf-ask --check AGENT` tells the agent to run `cat` on a file holding a random word and fails if the word comes back.
`elf-ask` fails closed: it asks an agent only after it passed that check at the version installed now (kept in
`ask-checks.json` in the data folder; a new version is checked again first, so a flag it no longer knows or a tool
it gained can't slip through). The agent also gets no GitHub or SSH credentials and none of the runner's `ELF_*`
values. Codex's and pi's own lock-down is what's relied on: unlike the agents that work on code, they don't run in
the runner's sandbox yet. The agents that work on code (review, fix, Find out) still use Claude
Code.
