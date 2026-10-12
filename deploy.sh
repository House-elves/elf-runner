#!/usr/bin/env bash
# Builds the runner into a jar and deploys it to the local elf-runner.service.
# Refuses while an agent is running (a restart would stop it), unless --force.
set -euo pipefail
cd "$(dirname "$0")"

BIN="$HOME/.local/share/elf-runner/bin"
UNIT="$HOME/.config/systemd/user/elf-runner.service"

if [ "${1:-}" != "--force" ] && [ "$(pgrep -x bwrap | wc -l)" != "0" ]; then
  echo "An agent is running; deploy when it's done, or use --force to stop it." >&2
  exit 1
fi

mkdir -p "$BIN" "$(dirname "$UNIT")"
jbang export fatjar --force --output "$BIN/elf-runner-new.jar" ElfRunner.java >/dev/null 2>&1
mv "$BIN/elf-runner-new.jar" "$BIN/elf-runner.jar"

# A transient elf-runner unit (from systemd-run) left loaded would hide the unit file.
if [ "$(systemctl --user show elf-runner -p Transient --value 2>/dev/null)" = "yes" ]; then
  systemctl --user stop elf-runner.service 2>/dev/null || true
  systemctl --user reset-failed elf-runner.service 2>/dev/null || true
fi
if ! cmp -s deploy/elf-runner.service "$UNIT"; then
  cp deploy/elf-runner.service "$UNIT"
  systemctl --user daemon-reload
fi
systemctl --user enable elf-runner.service >/dev/null 2>&1 || true
systemctl --user restart elf-runner.service
sleep 5
journalctl --user -u elf-runner -n 3 --no-pager -o cat
