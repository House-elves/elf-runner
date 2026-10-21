import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The elves installed on this machine. Each is a clone of its repo in {@code elves/<name>} of the House Elves data
 * directory, checked out at the commit you installed or last updated to, with an {@code elf.yaml} that says what the
 * elf is: its settings, the commands the desk may run, its schedule and what it writes.
 *
 * <p>
 * An elf runs as you, outside the agent's sandbox (it holds its own passwords and API keys), so only the commit you
 * looked at runs: the clone never moves unless you install or update it from the desk.
 */
class Elves {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,40}");
    static final Pattern MAIN = Pattern.compile("[A-Za-z0-9_]{1,80}\\.java");
    static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,60}");
    static final Pattern ARG = Pattern.compile("--[a-z0-9-]{1,40}(=[A-Za-z0-9._:-]{1,80})?");
    static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    /** Names an elf can't take: House Elves' own, and the folders under ~/.config that hold logins. */
    static final Set<String> RESERVED = Set.of("house-elves", "elf-runner", "elf-desk", "gh", "git", "systemd", "jbang", "claude");
    static final Pattern TOOL = Pattern.compile("[a-z0-9][a-z0-9.-]{0,30}");

    /** What the desk may run: a name, a label for its button, and the fixed arguments it gets. */
    record Command(String name, String label, List<String> args) {
    }

    /** A schedule: the command, and the setting that holds when (or the fallback if there's no such setting). */
    record Schedule(String command, String setting, String fallback) {
    }

    /** One elf's elf.yaml, checked. */
    record Manifest(String name, String description, String main, Path config, List<String> tools,
            List<ElfRunner.Setting> settings, Map<String, Command> commands, Schedule schedule, String dryRun,
            List<String> reads, List<String> writes) {
    }

    /** An installed elf: its manifest, folder, repo and the commit it's at. */
    record Installed(Manifest manifest, Path dir, String repo, String commit) {
    }

    final Path dir;
    final Path state;
    final Path logs;
    final String githubUser;

    Elves(Path data, String githubUser) {
        this.dir = data.resolve("elves");
        this.state = data.resolve("elf-state");
        this.logs = data.resolve("elf-logs");
        this.githubUser = githubUser;
    }

    // ---- elf.yaml ----

    /**
     * Reads an elf.yaml. Everything is checked, since it comes from someone's repo: the config file is the elf's own
     * (~/.config/<name>/config), arguments are plain options, and keys and kinds are the ones the desk knows how to show.
     */
    static Manifest parse(String yaml, Path folder) throws IOException {
        JsonNode y = YAML.readTree(yaml);
        if (y == null || !y.isObject()) throw new IOException("elf.yaml is empty");
        String name = text(y, "name");
        if (!NAME.matcher(name).matches()) throw new IOException("elf.yaml: name must be lowercase letters, digits and -");
        if (RESERVED.contains(name)) throw new IOException("elf.yaml: " + name + " is House Elves' own name");
        String main = text(y, "main");
        if (!MAIN.matcher(main).matches()) throw new IOException("elf.yaml: main must be a .java file in the elf's folder");
        if (folder != null && !Files.isRegularFile(folder.resolve(main))) throw new IOException("elf.yaml: there's no " + main);
        // Its own folder only: never another elf's config, House Elves' own, or another program's.
        if (!text(y, "config").equals("~/.config/" + name + "/config")) {
            throw new IOException("elf.yaml: config must be ~/.config/" + name + "/config");
        }
        Path config = ElfRunner.HOME.resolve(".config").resolve(name).resolve("config");

        List<ElfRunner.Setting> settings = new ArrayList<>();
        for (JsonNode s : y.path("settings")) {
            String key = text(s, "key");
            if (!KEY.matcher(key).matches()) throw new IOException("elf.yaml: setting keys are UPPER_CASE: " + key);
            ElfRunner.Kind kind;
            try {
                kind = ElfRunner.Kind.valueOf(text(s, "kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IOException("elf.yaml: " + key + " has an unknown kind");
            }
            List<String> goesWith = new ArrayList<>();
            s.path("goes-with").forEach(g -> goesWith.add(g.asText()));
            settings.add(new ElfRunner.Setting(key, clip(text(s, "label"), 80), clip(s.path("help").asText(""), 300), kind,
                    s.path("min").asInt(0), s.path("max").asInt(kind == ElfRunner.Kind.INT ? 999_999 : 0),
                    s.hasNonNull("default") ? s.path("default").asText() : null, List.copyOf(goesWith)));
        }
        for (ElfRunner.Setting s : settings) {
            for (String g : s.goesWith()) {
                if (settings.stream().noneMatch(x -> x.key().equals(g))) throw new IOException("elf.yaml: " + s.key() + " goes with an unknown " + g);
            }
        }

        Map<String, Command> commands = new LinkedHashMap<>();
        var fields = y.path("commands").fields();
        while (fields.hasNext()) {
            var f = fields.next();
            if (!NAME.matcher(f.getKey()).matches()) throw new IOException("elf.yaml: command names are lowercase: " + f.getKey());
            List<String> args = new ArrayList<>();
            for (JsonNode a : f.getValue().path("args")) {
                if (!ARG.matcher(a.asText()).matches()) throw new IOException("elf.yaml: " + f.getKey() + " has an argument that isn't a plain option");
                args.add(a.asText());
            }
            commands.put(f.getKey(), new Command(f.getKey(), clip(f.getValue().path("label").asText(f.getKey()), 80), List.copyOf(args)));
        }

        Schedule schedule = null;
        if (y.hasNonNull("schedule")) {
            JsonNode s = y.path("schedule");
            Schedule sch = new Schedule(text(s, "command"), s.path("setting").asText(null), s.path("default").asText(null));
            schedule = sch;
            if (!commands.containsKey(sch.command())) throw new IOException("elf.yaml: the schedule runs an unknown command");
            String value = sch.setting() != null ? settings.stream().filter(x -> x.key().equals(sch.setting()))
                    .map(ElfRunner.Setting::dflt).findFirst().orElseThrow(() -> new IOException("elf.yaml: the schedule's setting isn't a setting"))
                    : sch.fallback();
            if (value != null && Calendar.parse(value) == null) throw new IOException("elf.yaml: the schedule isn't a calendar time");
        }
        String dryRun = y.path("dry-run").asText(null);
        if (dryRun != null && !commands.containsKey(dryRun)) throw new IOException("elf.yaml: dry-run names an unknown command");

        List<String> tools = new ArrayList<>();
        for (JsonNode t : y.path("tools")) {
            if (!TOOL.matcher(t.asText()).matches()) throw new IOException("elf.yaml: a tool is a command name");
            tools.add(t.asText());
        }
        return new Manifest(name, clip(y.path("description").asText(""), 300), main, config, List.copyOf(tools),
                List.copyOf(settings), commands, schedule, dryRun, lines(y.path("reads")), lines(y.path("writes")));
    }

    static String text(JsonNode n, String field) throws IOException {
        String v = n.path(field).asText("").strip();
        if (v.isEmpty()) throw new IOException("elf.yaml: " + field + " is missing");
        return v;
    }

    static List<String> lines(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(clip(x.asText(), 200)));
        return List.copyOf(out);
    }

    static String clip(String s, int max) {
        return ElfRunner.clip(s.strip(), max);
    }

    // ---- what's installed ----

    /** The installed elves, by name. A folder whose elf.yaml doesn't read is left out (and logged). */
    Map<String, Installed> installed() {
        Map<String, Installed> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (var list = Files.list(dir)) {
            for (Path d : list.filter(Files::isDirectory).toList()) {
                String name = d.getFileName().toString();
                if (!NAME.matcher(name).matches()) continue;
                try {
                    Manifest m = parse(Files.readString(d.resolve("elf.yaml")), d);
                    if (!m.name().equals(name)) throw new IOException("its elf.yaml says it's " + m.name());
                    out.put(name, new Installed(m, d, git(d, "config", "--get", "remote.origin.url")
                            .replaceFirst("^https://github.com/", "").replaceFirst("\\.git$", ""), git(d, "rev-parse", "HEAD")));
                } catch (Exception e) {
                    ElfRunner.log("Skipping the elf in " + d + ": " + ElfRunner.rootMessage(e));
                }
            }
        } catch (IOException e) {
            ElfRunner.log("Can't read " + dir + ": " + e.getMessage());
        }
        return out;
    }

    Installed get(String name) {
        Installed i = NAME.matcher(name).matches() ? installed().get(name) : null;
        if (i == null) throw new IllegalArgumentException(name + " isn't installed");
        return i;
    }

    /** How to run an installed elf (jbang runs its main file), or, before it was installed here, its command on PATH. */
    List<String> command(String name) {
        Installed i = NAME.matcher(name).matches() ? installed().get(name) : null;
        if (i != null) return List.of(jbang(), "--quiet", i.dir().resolve(i.manifest().main()).toString());
        Path onPath = ElfRunner.which(name);
        if (onPath == null) throw new IllegalStateException(name + " isn't installed: add it on the desk's Elves page");
        return List.of(onPath.toString());
    }

    static String jbang() {
        Path p = ElfRunner.which("jbang");
        if (p != null) return p.toString();
        Path home = ElfRunner.HOME.resolve(".jbang/bin/jbang");
        return Files.isExecutable(home) ? home.toString() : "jbang";
    }

    // ---- install, update, remove ----

    /**
     * Clones {@code repo} at {@code commit} and installs it, under the name its elf.yaml gives. Its config file gets
     * each setting's default unless it already has a value (an elf you set up before keeps its settings).
     */
    synchronized Installed install(String repo, String commit) throws Exception {
        check(repo, commit);
        Files.createDirectories(dir);
        Path tmp = Files.createTempDirectory(dir, ".install-");
        try {
            gitAuth(tmp.getParent(), "clone", "--quiet", "--no-checkout", "https://github.com/" + repo + ".git", tmp.toString());
            git(tmp, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", commit);
            Manifest m = parse(Files.readString(tmp.resolve("elf.yaml")), tmp);
            Path target = dir.resolve(m.name());
            if (Files.exists(target)) throw new IllegalStateException(m.name() + " is already installed");
            Files.move(tmp, target);
            writeDefaults(m);
            Files.createDirectories(state);
            Files.writeString(state.resolve(m.name() + ".last"), String.valueOf(Instant.now().getEpochSecond()));
            ElfRunner.log("Installed " + m.name() + " from " + repo + " at " + commit.substring(0, 7));
            return get(m.name());
        } finally {
            if (Files.exists(tmp)) ElfRunner.deleteTree(tmp);
        }
    }

    /** Moves an installed elf to {@code commit} (one you've seen the changes up to), or leaves it as it was. */
    synchronized Installed update(String name, String commit) throws Exception {
        Installed i = get(name);
        check(i.repo(), commit);
        gitAuth(i.dir(), "fetch", "--quiet", "origin");
        String before = i.commit();
        git(i.dir(), "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", commit);
        try {
            Manifest m = parse(Files.readString(i.dir().resolve("elf.yaml")), i.dir());
            if (!m.name().equals(name)) throw new IOException("the new elf.yaml renames it to " + m.name());
            writeDefaults(m);
        } catch (Exception e) {
            git(i.dir(), "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", before);
            throw new IOException("Not updated: " + ElfRunner.rootMessage(e));
        }
        ElfRunner.log("Updated " + name + " to " + commit.substring(0, 7));
        return get(name);
    }

    /**
     * What updating to {@code commit} would change: the commits and the diff from the installed commit, read from the
     * elf's own clone (so it's exactly what would run). Nothing moves.
     */
    synchronized ObjectNode changes(String name, String commit) throws Exception {
        Installed i = get(name);
        check(i.repo(), commit);
        gitAuth(i.dir(), "fetch", "--quiet", "origin");
        ObjectNode out = ElfRunner.JSON.createObjectNode().put("elf", name).put("from", i.commit()).put("to", commit);
        var commits = out.putArray("commits");
        for (String line : git(i.dir(), "log", "--format=%h %s", i.commit() + ".." + commit).split("\n")) {
            if (!line.isBlank()) commits.add(clip(line, 200));
        }
        String diff = git(i.dir(), "diff", "--no-color", "--no-ext-diff", i.commit(), commit);
        out.put("truncated", diff.length() > MAX_DIFF).put("diff", diff.length() > MAX_DIFF ? diff.substring(0, MAX_DIFF) : diff);
        return out;
    }

    static final int MAX_DIFF = 400_000;

    /** Removes an elf's program. Its settings stay in its config file, for if you install it again. */
    synchronized void remove(String name) {
        Installed i = get(name);
        if (runs.keySet().stream().anyMatch(k -> k.startsWith(name + ":") && runs.get(k).isAlive())) {
            throw new IllegalStateException(name + " is running; remove it when it's done");
        }
        ElfRunner.deleteTree(i.dir());
        try {
            Files.deleteIfExists(state.resolve(name + ".last"));
        } catch (IOException ignored) {
        }
        ElfRunner.log("Removed " + name);
    }

    static void check(String repo, String commit) {
        if (!ElfRunner.REPO_NAME.matcher(repo).matches()) throw new IllegalArgumentException("Bad repo");
        if (!COMMIT.matcher(commit).matches()) throw new IllegalArgumentException("Bad commit");
    }

    /** Settings with a default that the config file doesn't have yet. The file is only yours to read. */
    void writeDefaults(Manifest m) throws IOException {
        Files.createDirectories(m.config().getParent());
        if (!Files.exists(m.config())) {
            Files.createFile(m.config(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        }
        Map<String, String> current = ElfRunner.readConfig(m.config());
        Map<String, String> add = new LinkedHashMap<>();
        for (ElfRunner.Setting s : m.settings()) {
            if (s.dflt() != null && !current.containsKey(s.key())) {
                add.put(s.key(), s.dflt().replace("${github-user}", githubUser == null ? "" : githubUser));
            }
        }
        if (!add.isEmpty()) ElfRunner.writeConfig(m.config(), add);
    }

    String gitAuth(Path in, String... args) throws Exception {
        if (githubUser == null) throw new IllegalStateException("Set GITHUB_USER in the runner's config");
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "credential.helper=",
                "-c", "credential.helper=!f() { echo username=x-access-token; echo \"password=$ELF_PULL_TOKEN\"; }; f"));
        cmd.addAll(List.of(args));
        return run(cmd, in, Map.of("ELF_PULL_TOKEN", ElfRunner.ghToken(githubUser)), 300);
    }

    static String git(Path in, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return run(cmd, in, Map.of(), 60);
    }

    static String run(List<String> cmd, Path in, Map<String, String> env, int seconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(in.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(cmd.get(0) + " took too long");
        }
        if (p.exitValue() != 0) throw new IOException(ElfRunner.clip(out, 300));
        return out;
    }

    // ---- runs ----

    /** Runs by key: a built-in tab's mode (email, status, …) or elf:command. */
    final Map<String, Process> runs = new ConcurrentHashMap<>();

    /**
     * Starts an elf in the background, its output going to elf-logs/{key}.log. A run ends on its own; a failed one
     * stays failed (its log's end shown on the desk) until the next run of that key.
     */
    synchronized void start(String key, String elf, List<String> args) throws Exception {
        Process old = runs.get(key);
        if (old != null && old.isAlive()) throw new IllegalStateException("It's already running; wait for it to finish");
        List<String> cmd = new ArrayList<>(command(elf));
        cmd.addAll(args);
        Files.createDirectories(logs);
        Path log = logs.resolve(key + ".log");
        Files.deleteIfExists(logs.resolve(key + ".failed"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(ElfRunner.HOME.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        Process p = pb.start();
        runs.put(key, p);
        p.onExit().thenAccept(done -> {
            if (done.exitValue() != 0) {
                try {
                    Files.writeString(logs.resolve(key + ".failed"), String.valueOf(done.exitValue()));
                } catch (IOException ignored) {
                }
            }
            ElfRunner.log(elf + " (" + key + ") " + (done.exitValue() == 0 ? "finished" : "failed with " + done.exitValue()));
            if (onChange != null) onChange.run();
        });
        ElfRunner.log("Started " + elf + " (" + key + ")");
        if (onChange != null) onChange.run();
    }

    volatile Runnable onChange;

    boolean running(String key) {
        Process p = runs.get(key);
        return p != null && p.isAlive();
    }

    /** Keys whose run is going now. */
    List<String> runningKeys() {
        return runs.entrySet().stream().filter(e -> e.getValue().isAlive()).map(Map.Entry::getKey).sorted().toList();
    }

    /** Keys whose last run failed, with the end of its log. */
    Map<String, String> failed() {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(logs)) return out;
        try (var list = Files.list(logs)) {
            for (Path f : list.filter(x -> x.toString().endsWith(".failed")).toList()) {
                String base = f.getFileName().toString().replaceFirst("\\.failed$", "");
                out.put(base, tail(logs.resolve(base + ".log")));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** The end of a key's last log, and whether it's still going or failed: what a dry run shows. */
    ObjectNode last(String key) {
        String base = key;
        ObjectNode n = ElfRunner.JSON.createObjectNode().put("key", key).put("running", running(key));
        Path log = logs.resolve(base + ".log");
        if (Files.isRegularFile(log)) {
            n.put("failed", Files.exists(logs.resolve(base + ".failed"))).put("output", tail(log));
            try {
                n.put("at", Files.getLastModifiedTime(log).toInstant().toString());
            } catch (IOException ignored) {
            }
        }
        return n;
    }

    static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log);
            return ElfRunner.clip(String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size())).strip(), 6000);
        } catch (IOException e) {
            return "";
        }
    }

    // ---- schedules ----

    /** The schedule's calendar time for an installed elf: its setting's value, or the default. */
    String calendar(Installed i) {
        Schedule s = i.manifest().schedule();
        if (s == null) return null;
        if (s.setting() == null) return s.fallback();
        try {
            String v = ElfRunner.readConfig(i.manifest().config()).get(s.setting());
            if (v != null && !v.isBlank()) return v;
        } catch (IOException ignored) {
        }
        return i.manifest().settings().stream().filter(x -> x.key().equals(s.setting())).map(ElfRunner.Setting::dflt)
                .findFirst().orElse(null);
    }

    Instant lastScheduled(String name) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(Files.readString(state.resolve(name + ".last")).strip()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Once a minute: an elf whose scheduled time has passed since its last scheduled run runs now. So a run missed
     * while the laptop slept (or the runner was down) happens when it's back, once.
     */
    void tick(ZonedDateTime now) {
        for (Installed i : installed().values()) {
            Schedule s = i.manifest().schedule();
            Calendar cal = Calendar.parse(calendar(i));
            if (s == null || cal == null) continue;
            String name = i.manifest().name();
            try {
                Files.createDirectories(state);
                Instant last = lastScheduled(name);
                if (last == null) {
                    Files.writeString(state.resolve(name + ".last"), String.valueOf(now.toEpochSecond()));
                    continue;
                }
                ZonedDateTime due = cal.previous(now);
                String key = name + ":" + s.command();
                if (due == null || !due.toInstant().isAfter(last) || running(key)) continue;
                Files.writeString(state.resolve(name + ".last"), String.valueOf(now.toEpochSecond()));
                ElfRunner.log("Scheduled run of " + name + " (" + calendar(i) + ")");
                start(key, name, i.manifest().commands().get(s.command()).args());
            } catch (Exception e) {
                ElfRunner.log("Couldn't run " + name + " on schedule: " + ElfRunner.rootMessage(e));
            }
        }
    }

    void schedule() {
        Thread.ofVirtual().name("elf-schedules").start(() -> {
            while (true) {
                try {
                    tick(ZonedDateTime.now());
                    Thread.sleep(java.time.Duration.ofSeconds(60 - LocalTime.now().getSecond() + 1));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    ElfRunner.log("Schedules: " + ElfRunner.rootMessage(e));
                }
            }
        });
    }

    // ---- what the desk sees ----

    ObjectNode view(Installed i) {
        Manifest m = i.manifest();
        ObjectNode n = ElfRunner.JSON.createObjectNode().put("name", m.name()).put("description", m.description())
                .put("repo", i.repo()).put("commit", i.commit())
                .put("installed", Files.isRegularFile(m.config()));
        var tools = n.putArray("tools");
        for (String t : m.tools()) tools.addObject().put("name", t).put("found", ElfRunner.which(t) != null);
        var commands = n.putArray("commands");
        m.commands().values().forEach(c -> commands.addObject().put("name", c.name()).put("label", c.label()));
        if (m.dryRun() != null) n.put("dryRun", m.dryRun());
        m.reads().forEach(n.putArray("reads")::add);
        m.writes().forEach(n.putArray("writes")::add);
        if (m.schedule() != null) {
            String when = calendar(i);
            ObjectNode s = n.putObject("schedule").put("command", m.schedule().command()).put("calendar", when);
            Calendar cal = Calendar.parse(when);
            ZonedDateTime next = cal == null ? null : cal.next(ZonedDateTime.now());
            if (next != null) s.put("next", next.toInstant().toString());
            Instant last = lastScheduled(m.name());
            if (last != null) s.put("last", last.toString());
        }
        return n;
    }

    // ---- calendar times ----

    /**
     * The part of systemd's calendar format schedules use: optional weekdays, any date, a time of day, in local
     * time. {@code Mon *-*-* 08:00:00}, {@code Mon..Fri *-*-* 07:30}, {@code *-*-* 06:00:00}. It works the same on
     * Linux and macOS, so nothing asks systemd.
     */
    record Calendar(Set<DayOfWeek> days, LocalTime time) {

        static final Pattern FORMAT = Pattern.compile(
                "(?:([A-Za-z]{3}(?:\\.\\.[A-Za-z]{3})?(?:,[A-Za-z]{3}(?:\\.\\.[A-Za-z]{3})?)*) )?\\*-\\*-\\* (\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
        static final List<String> NAMES = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

        static Calendar parse(String v) {
            if (v == null) return null;
            Matcher m = FORMAT.matcher(v.strip());
            if (!m.matches()) return null;
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            if (m.group(1) == null) {
                days.addAll(EnumSet.allOf(DayOfWeek.class));
            } else {
                for (String part : m.group(1).split(",")) {
                    String[] range = part.split("\\.\\.");
                    int from = NAMES.indexOf(range[0].toLowerCase(Locale.ROOT));
                    int to = NAMES.indexOf(range[range.length - 1].toLowerCase(Locale.ROOT));
                    if (from < 0 || to < 0 || to < from) return null;
                    for (int d = from; d <= to; d++) days.add(DayOfWeek.of(d + 1));
                }
            }
            int h = Integer.parseInt(m.group(2)), min = Integer.parseInt(m.group(3));
            int s = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            if (h > 23 || min > 59 || s > 59) return null;
            return new Calendar(days, LocalTime.of(h, min, s));
        }

        /** The latest time at or before {@code now}. */
        ZonedDateTime previous(ZonedDateTime now) {
            for (int back = 0; back <= 7; back++) {
                LocalDate d = now.toLocalDate().minusDays(back);
                ZonedDateTime t = d.atTime(time).atZone(now.getZone());
                if (days.contains(d.getDayOfWeek()) && !t.isAfter(now)) return t;
            }
            return null;
        }

        /** The first time after {@code now}. */
        ZonedDateTime next(ZonedDateTime now) {
            for (int ahead = 0; ahead <= 7; ahead++) {
                LocalDate d = now.toLocalDate().plusDays(ahead);
                ZonedDateTime t = d.atTime(time).atZone(now.getZone());
                if (days.contains(d.getDayOfWeek()) && t.isAfter(now)) return t;
            }
            return null;
        }
    }
}
