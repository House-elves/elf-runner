import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * elf-ask: how an elf asks a coding agent something. Prompt in, answer out, with nothing else: what the agent reads
 * includes other people's words (an email, a chat), so it gets no tools (built in, MCP, plugins, the web), none of
 * your settings or instructions files, and an empty folder of its own. Its answer is only text (or JSON in the shape
 * asked for), for the elf to check; it can't do anything.
 *
 * <p>
 * The runner gives every elf it starts {@code ELF_ASK} (this, as a command) and {@code ELF_ASK_AGENT} (the coding
 * agent the elf's settings chose, else the runner's). An elf run by hand has neither, and asks Claude itself.
 *
 * <pre>
 * elf-ask [--schema JSON] [--system TEXT] [--minutes N]    the prompt on stdin
 *   prints its answer as claude -p --output-format json does, whatever the agent: {"result" (the text),
 *   "structured_output" (with --schema), "total_cost_usd" (when the agent says), "agent"}; exit 1 and why on stderr
 * elf-ask --check [AGENT]                                    is it locked down? It's told to read a file and can't
 * </pre>
 *
 * <p>
 * It fails closed: an agent is only asked once it has passed that check at the version installed (kept in
 * ask-checks.json in the data folder, checked again when the version changes).
 */
public class ElfAsk {

    static final ObjectMapper JSON = new ObjectMapper();

    /** The agents an elf can ask, by the name its settings use, with what the desk calls them. */
    static final Map<String, String> AGENTS = new LinkedHashMap<>();
    static {
        AGENTS.put("claude", "Claude Code");
        AGENTS.put("codex", "Codex");
        AGENTS.put("pi", "pi");
        AGENTS.put("bob", "IBM Bob");
    }

    static final String DEFAULT = "claude";

    /** What an agent answered. */
    record Answer(String text, JsonNode json, Double cost) {
    }

    /** The command an agent's CLI is, on PATH: claude, codex, pi. */
    static String command(String agent) {
        return agent;
    }

    static boolean installed(String agent) {
        return ElfRunner.which(command(agent)) != null;
    }

    static int main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("--check")) {
                String agent = args.length > 1 ? args[1] : agentFromEnv();
                ObjectNode r = check(agent);
                System.out.println(r);
                return r.path("ok").asBoolean() ? 0 : 1;
            }
            String schema = null, system = null;
            int minutes = 5;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--schema" -> schema = args[++i];
                    case "--system" -> system = args[++i];
                    case "--minutes" -> minutes = Math.max(1, Math.min(30, Integer.parseInt(args[++i])));
                    default -> throw new IllegalArgumentException("elf-ask doesn't know " + args[i]);
                }
            }
            String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            if (prompt.isBlank()) throw new IllegalArgumentException("elf-ask reads its prompt on stdin, and it's empty");
            String agent = agentFromEnv();
            Answer a = ask(agent, prompt, schema == null ? null : JSON.readTree(schema), system, minutes);
            // In Claude Code's shape, which elves already read.
            ObjectNode out = JSON.createObjectNode().put("agent", agent).put("is_error", false).put("result", a.text());
            if (a.json() != null) out.set("structured_output", a.json());
            if (a.cost() != null) out.put("total_cost_usd", a.cost());
            System.out.println(out);
            return 0;
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage().replace('\n', ' ');
            // On stdout too, in the same shape, so an elf that only reads the answer says why.
            System.out.println(JSON.createObjectNode().put("agent", agentFromEnv()).put("is_error", true).put("result", why));
            System.err.println(why);
            return 1;
        }
    }

    static String agentFromEnv() {
        String a = System.getenv("ELF_ASK_AGENT");
        return a == null || a.isBlank() ? DEFAULT : a.strip();
    }

    /** Asks {@code agent}, locked down. {@code schema}: the answer is JSON of that shape. */
    static Answer ask(String agent, String prompt, JsonNode schema, String system, int minutes) throws Exception {
        if (!AGENTS.containsKey(agent)) throw new IllegalArgumentException("Not a coding agent elves can ask: " + agent);
        if (!installed(agent)) {
            throw new IllegalStateException(AGENTS.get(agent) + " isn't installed here (no " + command(agent)
                    + " on the PATH). Install it, or choose another coding agent in the elf's settings.");
        }
        locked(agent);
        return askUnchecked(agent, prompt, schema, system, minutes);
    }

    /** Asks without the lock-down check first: for the check itself. */
    static Answer askUnchecked(String agent, String prompt, JsonNode schema, String system, int minutes) throws Exception {
        Path dir = Files.createTempDirectory("elf-ask-");
        try {
            return switch (agent) {
                case "claude" -> claude(dir, prompt, schema, system, minutes);
                case "codex" -> codex(dir, prompt, schema, system, minutes);
                case "pi" -> pi(dir, prompt, schema, system, minutes);
                case "bob" -> bob(dir, prompt, schema, system, minutes);
                default -> throw new IllegalStateException(agent);
            };
        } finally {
            delete(dir);
        }
    }

    // ---- the agents ----

    /** Claude Code: no tools, no MCP, none of your settings (hooks, plugins, permissions). Says what it cost. */
    static Answer claude(Path dir, String prompt, JsonNode schema, String system, int minutes) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(command("claude"), "-p", "--model", "sonnet", "--output-format", "json",
                "--tools", "", "--strict-mcp-config", "--setting-sources", ""));
        if (schema != null) cmd.addAll(List.of("--json-schema", schema.toString()));
        if (system != null) cmd.addAll(List.of("--append-system-prompt", system));
        Run r = run(cmd, dir, prompt, minutes, "Claude");
        JsonNode out;
        try {
            out = JSON.readTree(r.out());
        } catch (Exception e) {
            throw new IllegalStateException("Claude didn't answer: " + ElfRunner.clip(r.err().isBlank() ? r.out() : r.err(), 300));
        }
        Double cost = out.path("total_cost_usd").isNumber() ? out.path("total_cost_usd").asDouble() : null;
        if (r.exit() != 0 || out.path("is_error").asBoolean()) {
            throw new IllegalStateException("Claude failed: " + ElfRunner.clip(out.path("result").asText(r.err()), 300));
        }
        JsonNode json = schema == null ? null : out.path("structured_output");
        if (schema != null && !json.isObject()) json = parseJson(out.path("result").asText(""), "Claude");
        return new Answer(out.path("result").asText(""), json, cost);
    }

    /**
     * Codex: its read-only sandbox, and its tools switched off on top (shell, apps, plugins, the browser, images,
     * the web); none of your config.toml, rules or saved sessions. Says what it used in tokens, not dollars.
     */
    static Answer codex(Path dir, String prompt, JsonNode schema, String system, int minutes) throws Exception {
        Path work = Files.createDirectory(dir.resolve("work"));
        Path answer = dir.resolve("answer.txt");
        List<String> cmd = new ArrayList<>(List.of(command("codex"), "exec", "--ephemeral", "--skip-git-repo-check",
                "--ignore-user-config", "--ignore-rules", "--sandbox", "read-only", "--color", "never", "--json",
                "-C", work.toString(), "-o", answer.toString(), "-c", "web_search=\"disabled\""));
        for (String f : CODEX_OFF) cmd.addAll(List.of("-c", "features." + f + "=false"));
        if (schema != null) {
            Path s = dir.resolve("schema.json");
            Files.writeString(s, strict(schema.deepCopy()).toString());
            cmd.addAll(List.of("--output-schema", s.toString()));
        }
        cmd.add("-");
        Run r = run(cmd, dir, withSystem(system, prompt), minutes, "Codex");
        String failed = null;
        for (String line : r.out().split("\n")) {
            if (!line.startsWith("{")) continue;
            JsonNode e = JSON.readTree(line);
            String type = e.path("type").asText();
            if (type.equals("error") || type.equals("turn.failed")) {
                failed = e.path("message").asText(e.path("error").path("message").asText("it failed"));
            }
        }
        String text = Files.isRegularFile(answer) ? Files.readString(answer).strip() : "";
        if (r.exit() != 0 || text.isEmpty()) {
            throw new IllegalStateException("Codex failed: " + ElfRunner.clip(failed != null ? failed : r.err(), 300));
        }
        return new Answer(text, schema == null ? null : parseJson(text, "Codex"), null);
    }

    /** Codex's features that are tools, or reach outside: all off for an ask. Unknown names are ignored. */
    static final List<String> CODEX_OFF = List.of("shell_tool", "unified_exec", "apps", "plugins", "browser_use",
            "browser_use_external", "computer_use", "in_app_browser", "image_generation", "view_image", "hooks", "memories",
            "skill_search", "tool_suggest", "remote_plugin", "sleep_tool");

    /** pi: no tools, extensions, MCP, skills, prompt templates or context files (AGENTS.md), and no session kept. */
    static Answer pi(Path dir, String prompt, JsonNode schema, String system, int minutes) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(command("pi"), "-p", "--no-tools", "--no-extensions", "--no-mcp",
                "--no-skills", "--no-prompt-templates", "--no-themes", "--no-context-files", "--no-session", "--no-approve"));
        if (system != null) cmd.addAll(List.of("--append-system-prompt", system));
        String p = schema == null ? prompt : prompt + "\n\nAnswer with only one JSON object that matches this JSON Schema, "
                + "with nothing before or after it:\n" + schema;
        Run r = run(cmd, dir, p, minutes, "pi");
        String text = r.out().strip();
        if (r.exit() != 0 || text.isEmpty()) {
            throw new IllegalStateException("pi failed: " + ElfRunner.clip(r.err().isBlank() ? r.out() : r.err(), 300));
        }
        return new Answer(text, schema == null ? null : parseJson(text, "pi"), null);
    }

    /**
     * IBM Bob (Bob Shell's headless run): ask mode, with every tool group off (reading and editing files, commands,
     * the web, MCP, skills, todos, subagents, switching mode), in an empty workspace. Its API key (BOB_API_KEY, and
     * BOB_TEAM_ID for a general key) comes from the runner's settings and goes to Bob only. Says what it cost.
     */
    static Answer bob(Path dir, String prompt, JsonNode schema, String system, int minutes) throws Exception {
        Path work = Files.createDirectory(dir.resolve("work"));
        Map<String, String> runner = ElfRunner.readConfig(ElfRunner.Config.FILE);
        String key = runner.getOrDefault("BOB_API_KEY", "").strip();
        if (key.isEmpty()) throw new IllegalStateException("IBM Bob needs its API key: set it in the runner's settings");
        List<String> cmd = new ArrayList<>(List.of(command("bob"), "run", "-f", "json", "-w", work.toString(), "--mode", "ask",
                "--disable-mcp", "--disable-subagents", "--disable-tool-groups", String.join(",", BOB_OFF),
                "--max-turns", "3", "--log-level", "error"));
        String team = runner.getOrDefault("BOB_TEAM_ID", "").strip();
        if (!team.isEmpty()) cmd.addAll(List.of("--team-id", team));
        String p = withSystem(system, schema == null ? prompt : prompt + "\n\nAnswer with only one JSON object that "
                + "matches this JSON Schema, with nothing before or after it:\n" + schema);
        Run r = run(cmd, work, p, minutes, "IBM Bob", Map.of("BOB_API_KEY", key));
        JsonNode out = null;
        for (String line : r.out().split("\n")) {
            if (line.startsWith("{")) {
                try {
                    out = JSON.readTree(line);
                } catch (Exception ignored) {
                    // not its answer
                }
            }
        }
        String text = out == null ? "" : out.path("last_message").asText("").strip();
        if (r.exit() != 0 || out == null || !"success".equals(out.path("status").asText()) || text.isEmpty()) {
            throw new IllegalStateException("IBM Bob failed: " + ElfRunner.clip(r.err().isBlank() ? r.out() : r.err(), 300));
        }
        JsonNode cost = out.path("stats").path("session_costs");
        return new Answer(text, schema == null ? null : parseJson(text, "IBM Bob"), cost.isNumber() ? cost.asDouble() : null);
    }

    /** Bob Shell's tool groups: all off for an ask. */
    static final List<String> BOB_OFF = List.of("read", "edit", "execute", "browser", "mcp", "skill", "todo", "subagent", "mode");

    /** An agent without a system prompt of its own: the instructions go first, marked as such. */
    static String withSystem(String system, String prompt) {
        return system == null ? prompt : "<instructions>\n" + system + "\n</instructions>\n\n" + prompt;
    }

    /**
     * OpenAI's structured output wants every object closed (additionalProperties false) with all its properties
     * required: an optional one comes back empty instead.
     */
    static JsonNode strict(JsonNode schema) {
        if (schema instanceof ObjectNode o) {
            if ("object".equals(o.path("type").asText()) && o.path("properties").isObject()) {
                o.put("additionalProperties", false);
                ArrayNode req = o.putArray("required");
                o.path("properties").fieldNames().forEachRemaining(req::add);
            }
            for (Iterator<JsonNode> it = o.elements(); it.hasNext();) strict(it.next());
        } else if (schema instanceof ArrayNode a) {
            a.forEach(ElfAsk::strict);
        }
        return schema;
    }

    /** The JSON object in an answer (the whole of it, or inside a ```json block). */
    static JsonNode parseJson(String text, String who) {
        String t = text.strip();
        int from = t.indexOf('{'), to = t.lastIndexOf('}');
        if (from >= 0 && to > from) {
            try {
                JsonNode n = JSON.readTree(t.substring(from, to + 1));
                if (n.isObject()) return n;
            } catch (Exception ignored) {
                // said below
            }
        }
        throw new IllegalStateException(who + " didn't answer in the shape asked for: " + ElfRunner.clip(t, 200));
    }

    // ---- is it locked down? ----

    /**
     * Gives the agent a file with a random word in it, outside its folder, and tells it to read it. Locked down, it
     * can't: the word is never in its answer.
     */
    static ObjectNode check(String agent) throws Exception {
        ObjectNode r = JSON.createObjectNode().put("agent", agent);
        Path dir = Files.createTempDirectory("elf-ask-check-");
        try {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            String word = HexFormat.of().formatHex(b);
            Path file = dir.resolve("secret.txt");
            Files.writeString(file, word + "\n");
            // Worded as a command to run: told to "read" a file it has no tool for, Claude Code waits instead of answering.
            String prompt = "Run this shell command and reply with only its output: cat " + file
                    + "\nIf you can't run commands or read files, reply with exactly: NO-TOOLS";
            // Only a finished answer counts: one that never came (a timeout, a failure) proves nothing, so it doesn't pass.
            Answer a = askUnchecked(agent, prompt, null, null, 2);
            boolean leaked = a.text().contains(word);
            r.put("ok", !leaked).put("answer", ElfRunner.clip(a.text(), 200));
            if (leaked) r.put("why", AGENTS.getOrDefault(agent, agent) + " read a file it was told to: it isn't locked down, "
                    + "so elves mustn't ask it");
        } catch (Exception e) {
            r.put("ok", false).put("why", e.getMessage());
        } finally {
            delete(dir);
        }
        return r;
    }

    /**
     * Fails closed: an agent is asked only once it passed {@link #check} at the version installed now. The result is
     * kept per agent and version (ask-checks.json in the data folder), so a new version is checked again before an
     * elf asks it: a flag it no longer knows, or a tool it gained, can't slip through.
     */
    static synchronized void locked(String agent) throws Exception {
        String version = version(agent);
        Path file = data().resolve("ask-checks.json");
        ObjectNode checks = Files.isRegularFile(file) && JSON.readTree(file.toFile()) instanceof ObjectNode o ? o
                : JSON.createObjectNode();
        JsonNode seen = checks.path(agent);
        if (seen.path("version").asText("").equals(version) && seen.path("ok").asBoolean()) return;
        ObjectNode r = check(agent);
        r.put("version", version).put("at", java.time.Instant.now().toString());
        checks.set(agent, r);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(".ask-checks.json.tmp");
        Files.writeString(tmp, checks.toPrettyString());
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        if (!r.path("ok").asBoolean()) {
            // It answered with the file's word, or it couldn't be asked at all (no login, say): either way, not yet.
            throw new IllegalStateException(r.has("answer")
                    ? AGENTS.get(agent) + " " + version + " failed the lock-down check, so elves won't ask it: "
                            + r.path("why").asText("") + ". Choose another coding agent in the elf's settings."
                    : "Couldn't check that " + AGENTS.get(agent) + " is locked down, so elves won't ask it yet: "
                            + r.path("why").asText(""));
        }
    }

    /** What {@code agent --version} says: a new one is checked again. */
    static String version(String agent) throws Exception {
        Path dir = Files.createTempDirectory("elf-ask-version-");
        try {
            Run r = run(List.of(command(agent), "--version"), dir, "", 1, AGENTS.get(agent));
            String v = (r.out() + " " + r.err()).strip().replaceAll("\\s+", " ");
            if (r.exit() != 0 || v.isEmpty()) throw new IllegalStateException(AGENTS.get(agent) + " didn't say its version");
            return ElfRunner.clip(v, 80);
        } finally {
            delete(dir);
        }
    }

    /** The House Elves data folder, as the runner finds it. */
    static Path data() {
        String d = System.getenv("HOUSE_ELVES_DATA");
        return d != null && !d.isBlank() ? Path.of(d) : Path.of(System.getProperty("user.home"), ".local", "share", "house-elves");
    }

    // ---- running one ----

    record Run(int exit, String out, String err) {
    }

    /** The agent in its empty folder, the prompt on stdin, both outputs read while it runs. */
    static Run run(List<String> cmd, Path dir, String stdin, int minutes, String who) throws Exception {
        return run(cmd, dir, stdin, minutes, who, Map.of());
    }

    /** @param env the agent's own login, given to it alone */
    static Run run(List<String> cmd, Path dir, String stdin, int minutes, String who, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        scrub(pb.environment());
        pb.environment().putAll(env);
        Process p = pb.start();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
        try (var os = p.getOutputStream()) {
            os.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // it stopped reading: what it said is below
        }
        if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException(who + " took longer than " + minutes + " minutes");
        }
        return new Run(p.exitValue(), out.get(10, TimeUnit.SECONDS), err.get(10, TimeUnit.SECONDS).strip());
    }

    /**
     * Nothing it's not meant to have, in case a tool slips through after all: no GitHub or SSH credentials, and none
     * of what the runner gave the elf (its tokens, files, push and pull credentials). The agent's own login stays.
     */
    static void scrub(Map<String, String> env) {
        for (String k : List.of("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN",
                "GITHUB_PERSONAL_ACCESS_TOKEN", "SSH_AUTH_SOCK", "GIT_ASKPASS", "SSH_ASKPASS", "RUNNER_TOKEN", "BOB_API_KEY")) {
            env.remove(k);
        }
        env.keySet().removeIf(k -> k.startsWith("ELF_"));
        env.put("GIT_TERMINAL_PROMPT", "0");
    }

    static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void delete(Path dir) {
        try (var all = Files.walk(dir)) {
            all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // a temp folder: the OS clears it
        }
    }

    // ---- the command elves run ----

    /**
     * Writes {@code bin/elf-ask} in the runner's data folder: this JVM, with this classpath, running --ask. The
     * runner gives its path to every elf it starts, as ELF_ASK.
     */
    static Path writeLauncher(Path data) throws IOException {
        Path bin = Files.createDirectories(data.resolve("bin"));
        Path f = bin.resolve("elf-ask");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String cp = System.getProperty("java.class.path");
        String script = "#!/bin/sh\n# Written by the House Elves runner: how an elf asks a coding agent (ElfAsk.java).\n"
                + "exec " + quote(jvm) + " -Xmx128m -XX:TieredStopAtLevel=1 -cp " + quote(cp) + " ElfRunner --ask \"$@\"\n";
        if (!Files.isRegularFile(f) || !Files.readString(f).equals(script)) {
            Path tmp = bin.resolve(".elf-ask.tmp");
            Files.writeString(tmp, script);
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        return f;
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
