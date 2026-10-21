#!/bin/bash
# Runs inside the agent sandbox (ElfRunner --sandbox-shell) and checks what it can and can't do. $1 is the real
# home, where the CI job planted canaries. Prints one line per check; exits 1 if any check is wrong.
REAL_HOME="$1"
fails=0
must() {
  if out=$( (eval "$2") 2>&1 ); then echo "ok    $1"
  else echo "WRONG $1 (should work):"; echo "$out" | tail -8 | sed 's/^/      /'; fails=$((fails+1)); fi
}
mustnt() { if (eval "$2") >/dev/null 2>&1; then echo "WRONG $1 (should be refused)"; fails=$((fails+1)); else echo "ok    $1 refused"; fi; }

echo "HOME=$HOME TMPDIR=${TMPDIR:-} JAVA_HOME=$JAVA_HOME"
must "java runs" 'java -version'
must "maven runs" 'mvn -v'
must "git over https" 'git ls-remote https://github.com/quarkusio/quarkus HEAD'
must "maven downloads into its repo" 'mvn -q -B -ntp dependency:get -Dartifact=org.slf4j:slf4j-api:2.0.13 && test -f ~/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar'
must "write its own directory" 'touch ./probe && rm ./probe'
must "write its own home" 'touch ~/probe && rm ~/probe'
must "write its temp dir" 'f=$(mktemp "$TMPDIR/probe.XXXXXX") && rm "$f"'
must "java writes a temp file" "java '$(dirname "$0")/TempFile.java'"
must "the home it sees isn't the real one" '[ "$HOME" != "$REAL_HOME" ] || [ ! -e "$HOME/.ssh/elf-canary" ]'

mustnt "read ~/.ssh" "cat '$REAL_HOME/.ssh/elf-canary'"
mustnt "read gh's login" "cat '$REAL_HOME/.config/gh/hosts.yml'"
mustnt "list the real home" "ls '$REAL_HOME' | grep -q elf-canary-file"
# On Linux the agent's home is an empty tmpfs at the same path, so this can succeed in there: the job checks the
# real home for the file afterwards, from outside.
touch "$REAL_HOME/elf-escape" >/dev/null 2>&1
echo "info  tried writing the real home (the job checks the file)"

if [ "$(uname)" = Darwin ]; then
  mustnt "write the shared /tmp" 'touch /private/tmp/elf-shared && test -f /private/tmp/elf-shared'
  mustnt "read the Keychain" 'security find-generic-password -s elf-canary -w | grep -q elf-secret'
  mustnt "read the clipboard" 'pbpaste | grep -q elf-clipboard'
  mustnt "open an app outside the sandbox" 'open -g -a Calculator'
  mustnt "send Apple Events" 'osascript -e "tell application \"System Events\" to get name of every process"'
  # Refused, or accepted but never run outside: the job checks for the file after.
  launchctl submit -l elf.escape -- /usr/bin/touch "$REAL_HOME/elf-launchd-escape" >/dev/null 2>&1
  echo "info  launchctl submit exit $? (the job checks the file)"
fi
exit $((fails > 0))
