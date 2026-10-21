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

if [ "${1:-}" != "--force" ] && [ "$(pgrep -x bwrap; pgrep -x sandbox-exec)" != "" ]; then
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
