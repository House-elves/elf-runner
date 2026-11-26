#!/usr/bin/env bash
# Builds the runner into a jar and swaps it into the runner that house-elves installed, then restarts it.
# Refuses while an agent is running (a restart would stop it), unless --force.
set -euo pipefail
cd "$(dirname "$0")"

APP="${HOUSE_ELVES_DATA:-$HOME/.local/share/house-elves}/app"
if [ ! -f "$APP/elf-runner.jar" ]; then
  echo "No installed runner in $APP: run house-elves install first." >&2
  exit 1
fi

# An agent is a sandbox (bwrap, sandbox-exec) under the runner. Any bwrap on the machine won't do: Flatpak apps
# (Slack, Zulip, ...) run in one too.
agent_running() {
  local todo kids pid
  todo="$(pgrep -f -- "-jar $APP/elf-runner.jar" || true)"
  while [ -n "$todo" ]; do
    kids=""
    for pid in $todo; do
      case "$(ps -o comm= -p "$pid" 2>/dev/null)" in bwrap|sandbox-exec) return 0 ;; esac
      kids="$kids $(pgrep -P "$pid" || true)"
    done
    todo="$(echo $kids)"
  done
  return 1
}

if [ "${1:-}" != "--force" ] && agent_running; then
  echo "An agent is running; deploy when it's done, or use --force to stop it." >&2
  exit 1
fi

jbang export fatjar --force --output "$APP/elf-runner-new.jar" ElfRunner.java >/dev/null 2>&1
mv "$APP/elf-runner-new.jar" "$APP/elf-runner.jar"

if [ "$(uname)" = Darwin ]; then
  launchctl kickstart -k "gui/$(id -u)/io.github.house-elves.runner"
else
  systemctl --user restart house-elves-runner.service
fi
sleep 5
house-elves logs runner -n 3
