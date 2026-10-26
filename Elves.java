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
    static final Pattern VERSION = Pattern.compile("\\d{1,4}(\\.\\d{1,4}){0,3}([.-][A-Za-z0-9]{1,20})?");
    /** Names an elf can't take: House Elves' own, the folders under ~/.config that hold logins, and the desk's pages. */
    static final Set<String> RESERVED = Set.of("house-elves", "elf-runner", "elf-desk", "gh", "git", "systemd", "jbang", "claude",
            "elves", "settings", "dashboard");
    static final Pattern TOOL = Pattern.compile("[a-z0-9][a-z0-9.-]{0,30}");
    /** A command's param becomes an option: --name=value. An action's goes in JSON, so it may be camelCase. */
    static final Pattern COMMAND_PARAM = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    static final Pattern ACTION_PARAM = Pattern.compile("[a-z][A-Za-z0-9]{0,40}");
    static final Pattern OPTION_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
    /** A folder in the elf's ~/.config/<name>/ (never the config file), and the files its UI may read in it. */
    static final Pattern FOLDER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,40}");
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,120}");
    /** A file of the elf's UI: under ui/, plain names, and only what a page loads. */
    static final Pattern UI_FILE = Pattern.compile("ui(/[A-Za-z0-9_-][A-Za-z0-9._-]{0,80}){1,6}");
    static final Pattern UI_EXT = Pattern.compile(".*\\.(js|css|json|svg|png|jpg|webp|woff2)");

    /**
     * What the desk may run: a name, a label for its button, and the fixed arguments it gets. Its params (a quarter,
     * say) are added as {@code --name=value}, each checked against the elf.yaml's pattern first.
     */
    record Command(String name, String label, List<String> args, Map<String, Param> params) {
    }

    /**
     * Something an elf's own UI may ask for with values (mark this email read, send this draft): the fixed
     * arguments, and the values as JSON on the elf's stdin, each checked first. {@code ask}: the desk shows its
     * confirm bar first (with {@code confirm}, or the label), unless the elf.yaml says {@code ask: false}.
     */
    record Action(String name, String label, List<String> args, Map<String, Param> params, boolean ask, String confirm) {
    }

    /**
     * A value a command or action takes. kind: text (up to max characters, matching pattern if there is one),
     * email, int, ints (a list of positive numbers, up to max), bool; or one of a fixed list.
     */
    record Param(String name, String kind, int max, Pattern pattern, List<String> oneOf, boolean optional) {
    }

    /**
     * The elf's tab on the desk, drawn by its own UI: {@code page} is a module in its ui/ folder, run in a sandboxed
     * frame. {@code card}: it also has a card on the dashboard. {@code settings}: its page also draws its own settings,
     * at the top of the desk's Settings pop-up for it.
     */
    record Tab(String page, boolean card, boolean settings) {
    }

    /**
     * How the elf brings its tab up to date: the command its Refresh, the dashboard's Refresh everything and its first
     * run after setup start, and when it runs on its own ({@code schedule}, a calendar time, until you set another
     * on the desk; null: only when you start it).
     */
    record Refresh(String command, String schedule) {
    }

    /** One elf's elf.yaml, checked. {@code title}: its name on the desk (the tab's, the card's). */
    record Manifest(String name, String title, String description, String main, Path config, List<String> tools,
            List<ElfRunner.Setting> settings, Map<String, Command> commands, Refresh refresh, String dryRun,
            List<String> reads, List<String> writes, List<String> data, Tab tab, Map<String, Action> actions, boolean agents,
            String agentDone, String version, String author) {

        /** The folder in ~/.config/<name>/ that its UI may read. */
        Path data(String folder) {
            if (!data.contains(folder)) throw new IllegalArgumentException(name + "'s UI doesn't read " + folder);
            return config.getParent().resolve(folder);
        }
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
                    s.hasNonNull("default") ? s.path("default").asText() : null, List.copyOf(goesWith),
                    s.path("optional").asBoolean(false)));
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
            Map<String, Param> params = params(f.getKey(), f.getValue().path("params"), true);
            commands.put(f.getKey(), new Command(f.getKey(), clip(f.getValue().path("label").asText(f.getKey()), 80), List.copyOf(args), params));
        }

        Map<String, Action> actions = new LinkedHashMap<>();
        var acts = y.path("actions").fields();
        while (acts.hasNext()) {
            var f = acts.next();
            if (!NAME.matcher(f.getKey()).matches()) throw new IOException("elf.yaml: action names are lowercase: " + f.getKey());
            JsonNode a = f.getValue();
            List<String> args = new ArrayList<>();
            for (JsonNode x : a.path("args")) {
                if (!ARG.matcher(x.asText()).matches()) throw new IOException("elf.yaml: " + f.getKey() + " has an argument that isn't a plain option");
                args.add(x.asText());
            }
            if (args.isEmpty()) throw new IOException("elf.yaml: the action " + f.getKey() + " has no args");
            boolean ask = !a.path("ask").isBoolean() || a.path("ask").asBoolean();
            String label = clip(a.path("label").asText(f.getKey()), 80);
            actions.put(f.getKey(), new Action(f.getKey(), label, List.copyOf(args), params(f.getKey(), a.path("params"), false), ask,
                    clip(a.path("confirm").asText(label), 200)));
        }

        List<String> data = new ArrayList<>();
        for (JsonNode d : y.path("data")) {
            if (!FOLDER.matcher(d.asText()).matches() || d.asText().equals("config")) {
                throw new IOException("elf.yaml: data names folders in ~/.config/" + name + "/");
            }
            data.add(d.asText());
        }

        // The tab, and how it refreshes. Before 0.32 an elf.yaml listed tabs (each with its refresh) and a schedule
        // read from a setting: an elf installed then still reads, with its first tab and that schedule's default.
        JsonNode t = y.hasNonNull("tab") ? y.path("tab") : y.path("tabs").path(0);
        Tab tab = null;
        String refreshCommand = null, refreshSchedule = null;
        if (!t.isMissingNode() && !t.isNull()) {
            String page = text(t, "page");
            if (!UI_FILE.matcher(page).matches() || !page.endsWith(".js")) throw new IOException("elf.yaml: the tab's page is a .js file in ui/");
            if (folder != null && !Files.isRegularFile(folder.resolve(page))) throw new IOException("elf.yaml: there's no " + page);
            tab = new Tab(page, t.path("card").asBoolean(false), t.path("settings").asBoolean(false));
            refreshCommand = t.path("refresh").asText(null);
        }
        if (y.hasNonNull("refresh")) {
            JsonNode r = y.path("refresh");
            refreshCommand = r.isTextual() ? r.asText() : text(r, "command");
            refreshSchedule = r.path("schedule").asText(null);
        } else if (y.hasNonNull("schedule")) {
            JsonNode s = y.path("schedule");
            refreshCommand = text(s, "command");
            String setting = s.path("setting").asText(null);
            refreshSchedule = setting == null ? s.path("default").asText(null) : settings.stream()
                    .filter(x -> x.key().equals(setting)).map(ElfRunner.Setting::dflt).findFirst().orElse(null);
        }
        Refresh refresh = null;
        if (refreshCommand != null) {
            Command c = commands.get(refreshCommand);
            if (c == null) throw new IOException("elf.yaml: it refreshes with an unknown command");
            if (c.params().values().stream().anyMatch(x -> !x.optional())) {
                throw new IOException("elf.yaml: its refresh command needs values nobody gives it");
            }
            if (refreshSchedule != null && Calendar.parse(refreshSchedule) == null) throw new IOException("elf.yaml: the refresh schedule isn't a calendar time");
            refresh = new Refresh(refreshCommand, refreshSchedule);
        }
        // An elf that starts agents gets their reports in its agents folder (see ElfAgents).
        // agents: true, or {done: <action>}, which the runner runs with {id} when one of its agents ends.
        JsonNode ag = y.path("agents");
        boolean agents = ag.isObject() || ag.asBoolean(false);
        String agentDone = ag.path("done").asText(null);
        if (agentDone != null && !actions.containsKey(agentDone)) throw new IOException("elf.yaml: agents.done names an unknown action");
        if (agents && !data.contains("agents")) data.add("agents");
        // Its own version (1.2.0) and who made it (a GitHub login), for the desk to show; both optional.
        String version = y.path("version").asText(null);
        if (version != null && !VERSION.matcher(version).matches()) throw new IOException("elf.yaml: version is like 1.2.0");
        String author = y.path("author").asText(null);
        if (author != null) author = author.replaceFirst("^@", "");
        if (author != null && !ElfRunner.LOGIN.matcher(author).matches()) throw new IOException("elf.yaml: author is a GitHub login");
        String title = clip(y.path("title").asText(name), 30);
        if (title.isEmpty()) title = name;
        String dryRun = y.path("dry-run").asText(null);
        if (dryRun != null && !commands.containsKey(dryRun)) throw new IOException("elf.yaml: dry-run names an unknown command");

        List<String> tools = new ArrayList<>();
        for (JsonNode x : y.path("tools")) {
            if (!TOOL.matcher(x.asText()).matches()) throw new IOException("elf.yaml: a tool is a command name");
            tools.add(x.asText());
        }
        return new Manifest(name, title, clip(y.path("description").asText(""), 300), main, config, List.copyOf(tools),
                List.copyOf(settings), commands, refresh, dryRun, lines(y.path("reads")), lines(y.path("writes")),
                List.copyOf(data), tab, actions, agents, agentDone, version, author);
    }

    /** A command's or action's params. A command's are options, so each needs a pattern or a list to be one of. */
    static Map<String, Param> params(String owner, JsonNode n, boolean command) throws IOException {
        Map<String, Param> out = new LinkedHashMap<>();
        var fields = n.fields();
        while (fields.hasNext()) {
            var f = fields.next();
            String name = f.getKey();
            JsonNode p = f.getValue();
            if (!(command ? COMMAND_PARAM : ACTION_PARAM).matcher(name).matches()) throw new IOException("elf.yaml: " + owner + " has a bad param name: " + name);
            List<String> oneOf = new ArrayList<>();
            p.path("one-of").forEach(x -> oneOf.add(x.asText()));
            String kind = p.path("kind").asText(oneOf.isEmpty() ? "text" : "one-of");
            if (!Set.of("text", "email", "int", "ints", "bool", "one-of").contains(kind)) throw new IOException("elf.yaml: " + owner + "." + name + " has an unknown kind");
            if (kind.equals("one-of") && oneOf.isEmpty()) throw new IOException("elf.yaml: " + owner + "." + name + " lists nothing to be one of");
            Pattern pattern;
            try {
                pattern = p.hasNonNull("pattern") ? Pattern.compile(p.path("pattern").asText()) : null;
            } catch (Exception e) {
                throw new IOException("elf.yaml: " + owner + "." + name + " has a bad pattern");
            }
            int max = Math.min(p.path("max").asInt(kind.equals("ints") ? 100 : 200), kind.equals("ints") ? 1000 : 200_000);
            if (command) {
                if (pattern == null && oneOf.isEmpty()) throw new IOException("elf.yaml: " + owner + "." + name + " needs a pattern or one-of");
                if (oneOf.stream().anyMatch(x -> !OPTION_VALUE.matcher(x).matches())) throw new IOException("elf.yaml: " + owner + "." + name + " lists a value that can't be an option");
                if (!kind.equals("text") && !kind.equals("one-of")) throw new IOException("elf.yaml: a command's params are text");
            }
            out.put(name, new Param(name, kind, max, pattern, List.copyOf(oneOf), p.path("optional").asBoolean(false)));
        }
        return out;
    }

    /** The values given for params, checked: only declared ones, each the right kind. */
    static ObjectNode values(Map<String, Param> params, JsonNode given) {
        ObjectNode out = ElfRunner.JSON.createObjectNode();
        if (given != null && !given.isMissingNode() && !given.isNull()) {
            if (!given.isObject()) throw new IllegalArgumentException("Bad values");
            given.fieldNames().forEachRemaining(k -> {
                if (!params.containsKey(k)) throw new IllegalArgumentException("Not a value it takes: " + k);
            });
        }
        for (Param p : params.values()) {
            JsonNode v = given == null ? null : given.get(p.name());
            if (v == null || v.isNull()) {
                if (!p.optional()) throw new IllegalArgumentException(p.name() + " is missing");
                continue;
            }
            String bad = p.name() + " isn't valid";
            switch (p.kind()) {
                case "int" -> {
                    if (!v.isIntegralNumber()) throw new IllegalArgumentException(bad);
                    out.put(p.name(), v.asLong());
                }
                case "bool" -> {
                    if (!v.isBoolean()) throw new IllegalArgumentException(bad);
                    out.put(p.name(), v.asBoolean());
                }
                case "ints" -> {
                    if (!v.isArray() || v.size() > p.max()) throw new IllegalArgumentException(bad);
                    var a = out.putArray(p.name());
                    for (JsonNode x : v) {
                        if (!x.isIntegralNumber() || x.asLong() <= 0) throw new IllegalArgumentException(bad);
                        a.add(x.asLong());
                    }
                }
                default -> {
                    if (!v.isTextual()) throw new IllegalArgumentException(bad);
                    String s = v.asText();
                    boolean ok = switch (p.kind()) {
                        case "one-of" -> p.oneOf().contains(s);
                        case "email" -> ElfRunner.EMAIL.matcher(s).matches();
                        default -> s.length() <= p.max() && (p.pattern() == null || p.pattern().matcher(s).matches());
                    };
                    if (!ok || s.chars().anyMatch(c -> c == 0)) throw new IllegalArgumentException(bad);
                    out.put(p.name(), s);
                }
            }
        }
        return out;
    }

    /** A command's arguments with its params' values as options, each checked. */
    static List<String> args(Command c, JsonNode given) {
        ObjectNode v = values(c.params(), given);
        List<String> args = new ArrayList<>(c.args());
        v.fields().forEachRemaining(f -> {
            String value = f.getValue().asText();
            if (!OPTION_VALUE.matcher(value).matches()) throw new IllegalArgumentException(f.getKey() + " isn't valid");
            args.add("--" + f.getKey() + "=" + value);
        });
        return args;
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

    // ---- checking an elf before it's installed ----

    /** What a published elf mustn't carry: tokens and keys, by their well-known prefixes, and private keys. */
    static final Pattern LEAK = Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|sk-ant-[A-Za-z0-9_-]{20,}"
            + "|xox[abpr]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|-----BEGIN [A-Z ]*PRIVATE KEY-----");
    static final Pattern EMAIL_IN_TEXT = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    /** Text files the leak check reads; the rest (images, fonts) it leaves alone. */
    static final Pattern TEXT_FILE = Pattern.compile(".*\\.(java|js|css|json|yaml|yml|md|txt|properties|xml|html|sh|svg)|[^.]*");

    /**
     * --check-elf: reads an elf's folder the way installing it does (its elf.yaml, every file it names), then what
     * the desk can't check for you: that it says what it reads and writes, and carries no tokens, keys or email
     * addresses but its author's. Prints what it found; 0 if it installs, 1 if it doesn't.
     */
    static int checkElf(Path folder) {
        Manifest m;
        try {
            m = parse(Files.readString(folder.resolve("elf.yaml")), folder);
        } catch (Exception e) {
            System.out.println("It won't install: " + ElfRunner.rootMessage(e));
            return 1;
        }
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (m.version() == null) warnings.add("It has no version: the desk shows a new version as an update.");
        if (m.author() == null) warnings.add("It has no author (your GitHub login).");
        if (m.description().isBlank()) problems.add("It has no description: the + page shows it.");
        if (m.reads().isEmpty()) problems.add("It doesn't say what it reads (reads:).");
        if (m.writes().isEmpty()) problems.add("It doesn't say what it writes (writes:), not even \"Nothing outside this machine\".");
        if (m.dryRun() == null) warnings.add("It has no dry-run: a command that shows what it would do, saving and sending nothing.");
        if (m.tab() == null) warnings.add("It has no tab: it can only be run from its settings.");
        if (!Files.isRegularFile(folder.resolve("README.md"))) problems.add("It has no README.md.");
        for (ElfRunner.Setting s : m.settings()) {
            if (SECRET_KEY.matcher(s.key()).matches() && s.kind() != ElfRunner.Kind.SECRET) {
                warnings.add(s.key() + " looks like a secret: give it kind: secret (the desk treats it as one anyway).");
            }
            if (s.kind() == ElfRunner.Kind.SECRET && s.dflt() != null && !s.dflt().isEmpty()) problems.add(s.key() + " is a secret with a default.");
        }
        for (Command c : m.commands().values()) {
            if (c.label().equals(c.name())) warnings.add("The command " + c.name() + " has no label.");
        }
        for (Action a : m.actions().values()) {
            if (a.label().equals(a.name())) warnings.add("The action " + a.name() + " has no label.");
            for (Param p : a.params().values()) {
                if (p.kind().equals("text") && p.pattern() == null) warnings.add(a.name() + "." + p.name() + " takes any text: give it a pattern if it can have one.");
            }
        }
        // What a public repo mustn't carry.
        String author = m.author() == null ? "" : m.author().toLowerCase(Locale.ROOT);
        try (var files = Files.walk(folder)) {
            for (Path f : files.toList()) {
                Path rel = folder.relativize(f);
                if (rel.startsWith(".git")) continue;
                // An elf is plain files: a link could point anywhere on this machine, so nothing behind one is read.
                if (Files.isSymbolicLink(f)) {
                    problems.add(rel + " is a symlink: an elf is plain files.");
                    continue;
                }
                if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                if (rel.startsWith(".git") || rel.startsWith("target") || !TEXT_FILE.matcher(rel.getFileName().toString()).matches()) continue;
                if (Files.size(f) > 2_000_000) {
                    warnings.add(rel + " is big for an elf (" + Files.size(f) / 1000 + " KB).");
                    continue;
                }
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher leak = LEAK.matcher(text);
                if (leak.find()) problems.add(rel + " has what looks like a token or key (" + leak.group().substring(0, 8) + "…).");
                Matcher mail = EMAIL_IN_TEXT.matcher(text);
                Set<String> found = new java.util.TreeSet<>();
                while (mail.find()) {
                    String e = mail.group().toLowerCase(Locale.ROOT);
                    if (e.endsWith("@example.com") || e.endsWith("@example.org") || e.contains("noreply") || e.startsWith("git@") || e.endsWith(".local")
                            || e.equals("notifications@github.com")
                            || (!author.isEmpty() && e.startsWith(author + "@"))) continue;
                    found.add(e);
                }
                if (!found.isEmpty()) warnings.add(rel + " has email addresses (" + String.join(", ", found) + "): make sure they may be public.");
            }
        } catch (IOException e) {
            problems.add("Couldn't read its files: " + e.getMessage());
        }

        System.out.println(m.title() + " (" + m.name() + (m.version() == null ? "" : " " + m.version()) + ") reads as the desk will read it.");
        System.out.println("  Settings: " + (m.settings().isEmpty() ? "none" : String.join(", ", m.settings().stream().map(ElfRunner.Setting::key).toList())));
        System.out.println("  Commands: " + (m.commands().isEmpty() ? "none" : String.join(", ", m.commands().keySet())));
        System.out.println("  Actions: " + (m.actions().isEmpty() ? "none" : String.join(", ", m.actions().keySet())));
        if (m.refresh() != null) System.out.println("  Refresh: " + m.refresh().command() + (m.refresh().schedule() == null ? ", when you start it" : ", " + m.refresh().schedule()));
        problems.forEach(p -> System.out.println("Problem: " + p));
        warnings.forEach(w -> System.out.println("Warning: " + w));
        if (!problems.isEmpty()) {
            System.out.println("Fix the problems before you publish it.");
            return 1;
        }
        return 0;
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
        return command(name, false);
    }

    /** {@code record}: a background run, whose output only goes to its log, may record the elf's classes for its AOT cache. */
    List<String> command(String name, boolean record) {
        Installed i = NAME.matcher(name).matches() ? installed().get(name) : null;
        if (i != null) {
            List<String> fast = launch(i, record);
            return fast != null ? fast : List.of(jbang(), "--quiet", i.dir().resolve(i.manifest().main()).toString());
        }
        Path onPath = ElfRunner.which(name);
        if (onPath == null) throw new IllegalStateException(name + " isn't installed: add it on the desk's Elves page");
        return List.of(onPath.toString());
    }

    // ---- starting an elf quickly ----
    // jbang works out an elf's java, classpath and main class on every start, which costs about half a second, and a
    // JVM loads its classes afresh each time. A click in an elf's tab is a start, so both add up. The runner asks
    // jbang once per installed commit (launch/<name>-<commit>.json) and starts java itself, with an AOT cache of the
    // elf's classes (JDK 25+, launch/<name>-<commit>.aot). The JVM prints a line on its output when it records one,
    // and an action's answer is its output, so only a background run (a refresh, say) records, and the cache is made
    // from that afterwards (assemble). Anything unusual: jbang as before.

    /** The java command for an installed elf, or null to start it with jbang. */
    List<String> launch(Installed i, boolean record) {
        try {
            Path dir = state.resolve("launch");
            String id = i.manifest().name() + "-" + i.commit();
            Path spec = dir.resolve(id + ".json");
            JsonNode l = Files.isRegularFile(spec) ? ElfRunner.JSON.readTree(spec.toFile()) : null;
            if (l == null || !runnable(l)) {
                l = launchSpec(i);
                if (l == null) return null;
                Files.createDirectories(dir);
                // Older commits' files go: an elf has one installed commit.
                Pattern mine = Pattern.compile(Pattern.quote(i.manifest().name()) + "-[0-9a-f]{40}\\..*");
                try (var old = Files.list(dir)) {
                    old.filter(f -> mine.matcher(f.getFileName().toString()).matches()
                            && !f.getFileName().toString().startsWith(id + ".")).forEach(f -> f.toFile().delete());
                }
                Path tmp = Files.createTempFile(dir, id, ".tmp");
                Files.writeString(tmp, l.toString());
                Files.move(tmp, spec, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            List<String> cmd = new ArrayList<>(List.of(l.path("java").asText()));
            if (l.path("aot").asBoolean()) {
                Path aot = dir.resolve(id + ".aot");
                // Its AOT cache, which the JVM checks against the classpath (and ignores, saying nothing, if it
                // doesn't match); or, for a background run and only one at a time, recording what it loads.
                cmd.add("-Xlog:aot=off,cds=off");
                if (Files.isRegularFile(aot)) cmd.add("-XX:AOTCache=" + aot);
                else if (record && claim(dir.resolve(id + ".recording"))) {
                    cmd.addAll(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + dir.resolve(id + ".aotconf")));
                }
            }
            l.path("options").forEach(o -> cmd.add(o.asText()));
            List<String> cp = new ArrayList<>();
            l.path("classpath").forEach(c -> cp.add(c.asText()));
            cmd.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, cp), l.path("main").asText()));
            return cmd;
        } catch (Exception e) {
            ElfRunner.log("Starting " + i.manifest().name() + " with jbang: " + e.getMessage());
            return null;
        }
    }

    /**
     * After a run that recorded: the AOT cache made from its recording, in the background, then moved into place
     * whole, so a start never reads half of one. If the run or this fails, the next run records again.
     */
    void assemble(Installed i, boolean ran) {
        Path dir = state.resolve("launch");
        String id = i.manifest().name() + "-" + i.commit();
        Path conf = dir.resolve(id + ".aotconf"), made = dir.resolve(id + ".aot.new");
        Thread.ofVirtual().start(() -> {
            try {
                JsonNode l = ElfRunner.JSON.readTree(dir.resolve(id + ".json").toFile());
                // Updated while it ran: that commit's cache would never be used.
                Installed now = installed().get(i.manifest().name());
                if (ran && Files.isRegularFile(conf) && now != null && now.commit().equals(i.commit())) {
                    List<String> cp = new ArrayList<>();
                    l.path("classpath").forEach(c -> cp.add(c.asText()));
                    List<String> cmd = new ArrayList<>(List.of(l.path("java").asText(), "-Xlog:aot=off,cds=off", "-XX:AOTMode=create",
                            "-XX:AOTConfiguration=" + conf, "-XX:AOTCache=" + made));
                    l.path("options").forEach(o -> cmd.add(o.asText()));
                    cmd.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, cp)));
                    run(cmd, dir, Map.of(), 300);
                    Files.move(made, dir.resolve(id + ".aot"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    ElfRunner.log(i.manifest().name() + " starts faster now (its AOT cache is made)");
                }
            } catch (Exception e) {
                ElfRunner.log("Couldn't make " + i.manifest().name() + "'s AOT cache: " + e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(conf);
                    Files.deleteIfExists(made);
                    Files.deleteIfExists(dir.resolve(id + ".recording"));
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** Its java and every jar on its classpath are still there (jbang's cache can be cleared). */
    static boolean runnable(JsonNode l) {
        if (!Files.isExecutable(Path.of(l.path("java").asText("/")))) return false;
        for (JsonNode c : l.path("classpath")) if (!Files.exists(Path.of(c.asText()))) return false;
        return l.path("classpath").size() > 0 && !l.path("main").asText("").isEmpty();
    }

    /** What jbang would run: built first, then its java, classpath, main class and runtime options. */
    JsonNode launchSpec(Installed i) throws Exception {
        Path main = i.dir().resolve(i.manifest().main());
        run(List.of(jbang(), "build", "--quiet", main.toString()), ElfRunner.HOME, Map.of(), 600);
        JsonNode info = ElfRunner.JSON.readTree(run(List.of(jbang(), "info", "tools", "--quiet", main.toString()), ElfRunner.HOME,
                Map.of(), 120));
        // A Java agent, a module path or a native build: jbang knows how; the runner doesn't try.
        for (String f : List.of("javaAgents", "moduleName", "modularPath")) {
            if (info.path(f).isArray() ? info.path(f).size() > 0 : !info.path(f).isMissingNode() && !info.path(f).isNull()) return null;
        }
        String jdk = info.path("availableJdkPath").asText("");
        String jar = info.path("applicationJar").asText("");
        String mainClass = info.path("mainClass").asText("");
        if (jdk.isEmpty() || jar.isEmpty() || mainClass.isEmpty()) return null;
        var l = ElfRunner.JSON.createObjectNode().put("java", Path.of(jdk, "bin", "java").toString()).put("main", mainClass)
                .put("aot", feature(Path.of(jdk)) >= 25);
        var cp = l.putArray("classpath").add(jar);
        info.path("resolvedDependencies").forEach(cp::add);
        var options = l.putArray("options");
        info.path("runtimeOptions").forEach(options::add);
        return runnable(l) ? l : null;
    }

    /** The JDK's feature version (25 of JAVA_VERSION="25.0.2" in its release file), or 0. */
    static int feature(Path jdk) {
        try {
            Matcher m = Pattern.compile("(?m)^JAVA_VERSION=\"(\\d+)").matcher(Files.readString(jdk.resolve("release")));
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Creates {@code marker}, if no one has: true for the one caller that did. */
    static boolean claim(Path marker) {
        try {
            Files.createFile(marker);
            return true;
        } catch (IOException e) {
            return false;
        }
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
        // The runner's own rule, whatever the desk asks: House-elves' elves, your own, and those from where you said
        // elves may also come from (ELF_SOURCES), nobody else's code.
        String owner = repo.substring(0, repo.indexOf('/'));
        List<String> sources = ElfRunner.elfSources();
        if (!owner.equalsIgnoreCase("House-elves") && (githubUser == null || !owner.equalsIgnoreCase(githubUser))
                && sources.stream().noneMatch(x -> x.equalsIgnoreCase(owner) || x.equalsIgnoreCase(repo))) {
            throw new IllegalArgumentException("Only elves from House-elves, " + githubUser
                    + " or the places in the + page's Settings can be installed");
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempDirectory(dir, ".install-");
        try {
            gitAuth(tmp.getParent(), "clone", "--quiet", "--no-checkout", "https://github.com/" + repo + ".git", tmp.toString());
            git(tmp, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", commit);
            Manifest m = parse(Files.readString(tmp.resolve("elf.yaml")), tmp);
            Path target = dir.resolve(m.name());
            if (Files.exists(target)) throw new IllegalStateException(m.name() + " is already installed");
            keepsSecrets(m);
            // A name stays with the repo it first came from: another repo never inherits its settings and secrets.
            Path from = state.resolve(m.name() + ".repo");
            if (Files.exists(from) && !Files.readString(from).strip().equalsIgnoreCase(repo)) {
                throw new IllegalStateException(m.name() + "'s settings belong to " + Files.readString(from).strip()
                        + ". To use this one instead, delete ~/.config/" + m.name() + "/config and " + from + " first");
            }
            Files.move(tmp, target);
            Files.createDirectories(state);
            Files.writeString(from, repo);
            rememberSecrets(m);
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
            keepsSecrets(m);
            writeDefaults(m);
            rememberSecrets(m);
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
        String next = changesFile(i.dir(), commit);
        out.put("hasNotes", !next.isBlank());
        var notes = out.putArray("notes");
        newNotes(changesFile(i.dir(), i.commit()), next, i.manifest().version())
                .forEach(n -> notes.addObject().put("title", n.title()).put("text", n.text()));
        return out;
    }

    static final int MAX_DIFF = 400_000;

    /** A section of an elf's CHANGES.md: a {@code ## } heading (its version) and what it says, for the person using it. */
    record Note(String title, String text) {
    }

    /** The elf's CHANGES.md at a commit, or nothing if it has none. */
    static String changesFile(Path dir, String commit) {
        try {
            return git(dir, "show", commit + ":CHANGES.md");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * What's new since the installed version, newest first: the sections of the new CHANGES.md headed by a higher
     * version than it ("## 1.3.0"), or, for a heading that isn't a version, one the installed CHANGES.md doesn't have.
     */
    static List<Note> newNotes(String installed, String next, String version) {
        Set<String> seen = new java.util.HashSet<>();
        sections(installed).forEach(n -> seen.add(n.title()));
        int[] have = versionOf(version);
        return sections(next).stream().filter(n -> {
            int[] v = versionOf(n.title());
            return v != null && have != null ? compare(v, have) > 0 : !seen.contains(n.title());
        }).limit(20).toList();
    }

    static final Pattern VERSION_AT_START = Pattern.compile("^v?(\\d{1,9}(?:\\.\\d{1,9}){0,3})(?![\\w.])");

    /** The version a heading or an elf.yaml starts with, like 1.3.0 of "1.3.0 (23 October 2026)", or null. */
    static int[] versionOf(String s) {
        Matcher m = s == null ? null : VERSION_AT_START.matcher(s.strip());
        if (m == null || !m.find()) return null;
        return java.util.Arrays.stream(m.group(1).split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

    static int compare(int[] a, int[] b) {
        for (int k = 0; k < Math.max(a.length, b.length); k++) {
            int c = Integer.compare(k < a.length ? a[k] : 0, k < b.length ? b[k] : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    static List<Note> sections(String md) {
        List<Note> out = new ArrayList<>();
        String title = null;
        StringBuilder text = new StringBuilder();
        for (String line : (md + "\n## ").split("\n")) {
            if (line.startsWith("## ")) {
                if (title != null && !title.isEmpty()) out.add(new Note(clip(title, 100), clip(text.toString(), 4000)));
                title = line.substring(3).strip();
                text.setLength(0);
            } else if (title != null) {
                text.append(line).append('\n');
            }
        }
        return out;
    }

    /** Removes an elf's program. Its settings stay in its config file, for if you install it again. */
    synchronized void remove(String name) {
        Installed i = get(name);
        if (runs.keySet().stream().anyMatch(k -> k.startsWith(name + ":") && runs.get(k).isAlive())) {
            throw new IllegalStateException(name + " is running; remove it when it's done");
        }
        ElfRunner.deleteTree(i.dir());
        try {
            Files.deleteIfExists(state.resolve(name + ".last"));
            Files.deleteIfExists(state.resolve(name + ".when"));
        } catch (IOException ignored) {
        }
        ElfRunner.log("Removed " + name);
    }

    /** Keys that look like secrets are secrets, whatever an elf.yaml says. */
    static final Pattern SECRET_KEY = Pattern.compile(".*(PASSWORD|API_KEY|TOKEN|SECRET).*");

    /**
     * Which settings are secret, and what each goes with, only ever grows: once a version of an elf said so (or the
     * key looks like a secret), a later elf.yaml can't make it plain text the desk would show, or drop what it's tied
     * to. Kept outside the elf's folder, so removing the elf doesn't forget it.
     */
    void rememberSecrets(Manifest m) throws IOException {
        keepsSecrets(m);
        Map<String, java.util.TreeSet<String>> known = secrets(m.name());
        for (ElfRunner.Setting s : m.settings()) {
            if (s.kind() == ElfRunner.Kind.SECRET || SECRET_KEY.matcher(s.key()).matches()) {
                known.computeIfAbsent(s.key(), k -> new java.util.TreeSet<>()).addAll(s.goesWith());
            }
        }
        Files.createDirectories(state);
        Files.writeString(state.resolve(m.name() + ".secrets"), ElfRunner.JSON.writeValueAsString(known));
    }

    /**
     * An elf.yaml has to keep listing every remembered secret and what it goes with: a version that dropped one would
     * leave the secret in the config file, in the elf's hands, with nothing asking for it again when its account changes.
     */
    void keepsSecrets(Manifest m) throws IOException {
        Set<String> keys = new java.util.HashSet<>();
        m.settings().forEach(x -> keys.add(x.key()));
        for (var e : secrets(m.name()).entrySet()) {
            if (!keys.contains(e.getKey())) throw new IOException("its elf.yaml no longer lists the secret " + e.getKey());
            List<String> missing = e.getValue().stream().filter(w -> !keys.contains(w)).toList();
            if (!missing.isEmpty()) {
                throw new IOException("its elf.yaml no longer lists " + String.join(", ", missing)
                        + ", which the secret " + e.getKey() + " goes with");
            }
        }
    }

    Map<String, java.util.TreeSet<String>> secrets(String name) {
        Map<String, java.util.TreeSet<String>> known = new TreeMap<>();
        try {
            JsonNode n = ElfRunner.JSON.readTree(Files.readString(state.resolve(name + ".secrets")));
            n.fields().forEachRemaining(f -> {
                var with = new java.util.TreeSet<String>();
                f.getValue().forEach(x -> with.add(x.asText()));
                known.put(f.getKey(), with);
            });
        } catch (Exception ignored) {
            // none remembered yet
        }
        return known;
    }

    /** An installed elf's settings as the desk may show them: remembered and secret-looking keys stay secrets. */
    List<ElfRunner.Setting> settings(Installed i) {
        Map<String, java.util.TreeSet<String>> known = secrets(i.manifest().name());
        List<ElfRunner.Setting> out = new ArrayList<>();
        for (ElfRunner.Setting s : i.manifest().settings()) {
            var with = new java.util.TreeSet<>(s.goesWith());
            boolean secret = s.kind() == ElfRunner.Kind.SECRET || SECRET_KEY.matcher(s.key()).matches() || known.containsKey(s.key());
            if (known.containsKey(s.key())) with.addAll(known.get(s.key()));
            out.add(secret ? new ElfRunner.Setting(s.key(), s.label(), s.help(), ElfRunner.Kind.SECRET, 0, 0, s.dflt(), List.copyOf(with), s.optional()) : s);
        }
        return out;
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
        List<String> cmd = new ArrayList<>(command(elf, true));
        boolean recording = cmd.stream().anyMatch(c -> c.equals("-XX:AOTMode=record"));
        Installed recorded = recording ? installed().get(elf) : null;
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
            if (recorded != null) assemble(recorded, done.exitValue() == 0);
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

    // ---- refreshing, and schedules ----

    /**
     * When an installed elf refreshes on its own: the calendar time you set on the desk (in elf-state/<name>.when,
     * "off" for never), or its elf.yaml's until you do. Null: only when you start it.
     */
    String calendar(Installed i) {
        Refresh r = i.manifest().refresh();
        if (r == null) return null;
        String set = chosen(i.manifest().name());
        if (set == null) return r.schedule();
        return set.equals("off") ? null : set;
    }

    /** What you set on the desk: a calendar time, "off", or null (nothing yet). */
    String chosen(String name) {
        try {
            String v = Files.readString(state.resolve(name + ".when")).strip();
            return v.equals("off") || Calendar.parse(v) != null ? v : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Sets when an elf refreshes on its own: a calendar time, null for never, or "default" for its elf.yaml's. It
     * starts counting from now, so a time already past today waits for its next turn.
     */
    synchronized void schedule(String name, String calendar) throws IOException {
        Installed i = get(name);
        if (i.manifest().refresh() == null) throw new IllegalArgumentException(name + " doesn't refresh");
        Files.createDirectories(state);
        Path when = state.resolve(name + ".when");
        if ("default".equals(calendar)) {
            Files.deleteIfExists(when);
        } else {
            String v = calendar == null || calendar.isBlank() ? "off" : calendar.strip();
            if (!v.equals("off") && (!ElfRunner.CALENDAR.matcher(v).matches() || Calendar.parse(v) == null)) {
                throw new IllegalArgumentException("Not a time it can run at");
            }
            Files.writeString(when, v);
        }
        Files.writeString(state.resolve(name + ".last"), String.valueOf(Instant.now().getEpochSecond()));
        ElfRunner.log(name + " refreshes " + (calendar(i) == null ? "only when you start it" : calendar(i)));
    }

    Instant lastScheduled(String name) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(Files.readString(state.resolve(name + ".last")).strip()));
        } catch (Exception e) {
            return null;
        }
    }

    /** The settings it can't run without that have no value yet: no default, not optional, empty in its config. */
    List<String> missing(Installed i) {
        Map<String, String> values;
        try {
            values = Files.isRegularFile(i.manifest().config()) ? ElfRunner.readConfig(i.manifest().config()) : Map.of();
        } catch (IOException e) {
            values = Map.of();
        }
        List<String> out = new ArrayList<>();
        for (ElfRunner.Setting s : settings(i)) {
            if (!s.optional() && s.dflt() == null && values.getOrDefault(s.key(), "").isBlank()) out.add(s.key());
        }
        return out;
    }

    /** Something in one of the folders its tab reads: it has run before (here, or before it was an elf). */
    boolean hasData(Installed i) {
        for (String folder : i.manifest().data()) {
            Path d = i.manifest().data(folder);
            if (!Files.isDirectory(d, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try (var list = Files.list(d)) {
                if (list.findAny().isPresent()) return true;
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    String refreshKey(Installed i) {
        return i.manifest().name() + ":" + i.manifest().refresh().command();
    }

    /**
     * Once it's set up, an elf's first refresh starts by itself, so its tab has something to show: when it was
     * installed with its settings already there, or when you save the last one it needs. Only the first time: not
     * once its refresh ran (or is running), nor for an elf that already has something saved for its tab.
     */
    void firstRefresh(String name) {
        try {
            Installed i = get(name);
            if (i.manifest().refresh() == null || !missing(i).isEmpty()) return;
            String key = refreshKey(i);
            if (running(key) || Files.exists(logs.resolve(key + ".log")) || hasData(i)) return;
            ElfRunner.log("First refresh of " + name);
            // Counts as its scheduled run: a time that passed while it wasn't set up doesn't run it again after.
            Files.createDirectories(state);
            Files.writeString(state.resolve(name + ".last"), String.valueOf(Instant.now().getEpochSecond()));
            start(key, name, args(i.manifest().commands().get(i.manifest().refresh().command()), null));
        } catch (Exception e) {
            ElfRunner.log("Couldn't start " + name + "'s first refresh: " + ElfRunner.rootMessage(e));
        }
    }

    /**
     * Once a minute: an elf whose refresh time has passed since its last scheduled run refreshes now. So a run missed
     * while the laptop slept (or the runner was down) happens when it's back, once. One that isn't set up yet waits.
     */
    void tick(ZonedDateTime now) {
        for (Installed i : installed().values()) {
            Calendar cal = Calendar.parse(calendar(i));
            if (cal == null) continue;
            String name = i.manifest().name();
            try {
                Files.createDirectories(state);
                Instant last = lastScheduled(name);
                if (last == null) {
                    Files.writeString(state.resolve(name + ".last"), String.valueOf(now.toEpochSecond()));
                    continue;
                }
                ZonedDateTime due = cal.previous(now);
                String key = refreshKey(i);
                if (due == null || !due.toInstant().isAfter(last) || running(key)) continue;
                if (!missing(i).isEmpty()) {
                    // Not set up: this turn is skipped, not saved up for when it is.
                    Files.writeString(state.resolve(name + ".last"), String.valueOf(now.toEpochSecond()));
                    continue;
                }
                Files.writeString(state.resolve(name + ".last"), String.valueOf(now.toEpochSecond()));
                ElfRunner.log("Scheduled refresh of " + name + " (" + calendar(i) + ")");
                start(key, name, args(i.manifest().commands().get(i.manifest().refresh().command()), null));
            } catch (Exception e) {
                ElfRunner.log("Couldn't refresh " + name + " on schedule: " + ElfRunner.rootMessage(e));
            }
        }
    }

    /** For elves installed before the runner kept these: which repo each came from, and its secrets. */
    void remember() {
        for (Installed i : installed().values()) {
            try {
                Files.createDirectories(state);
                Path from = state.resolve(i.manifest().name() + ".repo");
                if (!Files.exists(from)) Files.writeString(from, i.repo());
                rememberSecrets(i.manifest());
            } catch (IOException e) {
                ElfRunner.log("Couldn't note " + i.manifest().name() + "'s repo and secrets: " + e.getMessage());
            }
        }
    }

    void schedule() {
        remember();
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

    // ---- what an elf's UI reads ----

    static final int MAX_UI_FILE = 2 * 1024 * 1024, MAX_READ = 1536 * 1024, MAX_LISTED = 500;

    /** A file of the elf's UI, from ui/ at the installed commit. */
    static byte[] uiFile(Installed i, String path) throws IOException {
        if (path == null || !UI_FILE.matcher(path).matches() || !UI_EXT.matcher(path).matches()) {
            throw new IllegalArgumentException("Not a file of its UI");
        }
        Path f = inside(i.dir().resolve("ui"), i.dir().resolve(path));
        if (Files.size(f) > MAX_UI_FILE) throw new IOException(path + " is too big");
        return Files.readAllBytes(f);
    }

    /** The files in one of the elf's data folders, newest first: name, size, modified. */
    ObjectNode list(Installed i, String folder) throws IOException {
        Path dir = i.manifest().data(folder);
        ObjectNode out = ElfRunner.JSON.createObjectNode();
        var files = out.putArray("files");
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return out;
        record F(String name, long size, Instant modified) {
        }
        List<F> found = new ArrayList<>();
        try (var list = Files.list(dir)) {
            for (Path f : list.toList()) {
                String n = f.getFileName().toString();
                if (!FILE.matcher(n).matches() || !Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                found.add(new F(n, Files.size(f), Files.getLastModifiedTime(f).toInstant()));
            }
        }
        found.sort(java.util.Comparator.comparing(F::name).reversed());
        found.stream().limit(MAX_LISTED).forEach(f -> files.addObject().put("name", f.name()).put("size", f.size())
                .put("modified", f.modified().toString()));
        out.put("more", found.size() > MAX_LISTED);
        return out;
    }

    /**
     * Files from one of its data folders: {files: {name: text}}, or with {@code fields} (JSON files only) just those
     * top-level fields of each, for a list of runs. A missing file is left out.
     */
    ObjectNode read(Installed i, String folder, JsonNode names, JsonNode fields) throws IOException {
        Path dir = i.manifest().data(folder);
        if (!names.isArray() || names.isEmpty() || names.size() > 100) throw new IllegalArgumentException("Name 1 to 100 files");
        ObjectNode out = ElfRunner.JSON.createObjectNode();
        ObjectNode files = out.putObject("files");
        long total = 0;
        for (JsonNode n : names) {
            String name = n.asText();
            if (!FILE.matcher(name).matches()) throw new IllegalArgumentException("Bad file name: " + name);
            Path f = dir.resolve(name);
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            inside(dir, f);
            if (fields != null && fields.isArray() && !fields.isEmpty()) {
                ObjectNode picked = files.putObject(name);
                try {
                    JsonNode all = ElfRunner.JSON.readTree(f.toFile());
                    for (JsonNode k : fields) {
                        if (all.has(k.asText())) picked.set(k.asText(), all.get(k.asText()));
                    }
                } catch (IOException e) {
                    picked.put("unreadable", true);
                }
                total += picked.toString().length();
            } else {
                total += Files.size(f);
                if (total > MAX_READ) throw new IOException("That's too much to read at once");
                files.put(name, Files.readString(f));
            }
            if (total > MAX_READ) throw new IOException("That's too much to read at once");
        }
        return out;
    }

    /** {@code f}, once it's sure to be a regular file in {@code dir} with no link leading out. */
    static Path inside(Path dir, Path f) throws IOException {
        if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("No such file");
        Path real = f.toRealPath();
        if (!real.startsWith(dir.toRealPath())) throw new IOException("No such file");
        return real;
    }

    // ---- what the desk sees ----

    ObjectNode view(Installed i) {
        Manifest m = i.manifest();
        ObjectNode n = ElfRunner.JSON.createObjectNode().put("name", m.name()).put("title", m.title())
                .put("description", m.description()).put("repo", i.repo()).put("commit", i.commit())
                .put("installed", Files.isRegularFile(m.config()));
        try {
            n.put("installedAt", Files.getLastModifiedTime(state.resolve(m.name() + ".repo")).toInstant().toString());
        } catch (IOException ignored) {
        }
        n.putArray("missing").addAll(missing(i).stream().map(ElfRunner.JSON.getNodeFactory()::textNode).toList());
        var tools = n.putArray("tools");
        for (String t : m.tools()) tools.addObject().put("name", t).put("found", ElfRunner.which(t) != null);
        var commands = n.putArray("commands");
        m.commands().values().forEach(c -> commands.addObject().put("name", c.name()).put("label", c.label()));
        m.commands().values().forEach(c -> {
            if (!c.params().isEmpty()) {
                ObjectNode cmd = null;
                for (JsonNode x : commands) if (x.path("name").asText().equals(c.name())) cmd = (ObjectNode) x;
                var ps = cmd.putArray("params");
                c.params().keySet().forEach(ps::add);
            }
        });
        var actions = n.putArray("actions");
        m.actions().values().forEach(a -> actions.addObject().put("name", a.name()).put("label", a.label()).put("ask", a.ask())
                .put("confirm", a.confirm()));
        if (m.tab() != null) n.putObject("tab").put("page", m.tab().page()).put("card", m.tab().card())
                .put("settings", m.tab().settings());
        m.data().forEach(n.putArray("data")::add);
        n.put("agents", m.agents());
        if (m.version() != null) n.put("version", m.version());
        if (m.author() != null) n.put("author", m.author());
        if (m.dryRun() != null) n.put("dryRun", m.dryRun());
        m.reads().forEach(n.putArray("reads")::add);
        m.writes().forEach(n.putArray("writes")::add);
        if (m.refresh() != null) {
            String when = calendar(i);
            ObjectNode r = n.putObject("refresh").put("command", m.refresh().command()).put("calendar", when)
                    .put("default", m.refresh().schedule()).put("chosen", chosen(m.name()) != null);
            Calendar cal = Calendar.parse(when);
            ZonedDateTime next = cal == null ? null : cal.next(ZonedDateTime.now());
            if (next != null) r.put("next", next.toInstant().toString());
            Instant last = lastScheduled(m.name());
            if (last != null) r.put("last", last.toString());
        }
        return n;
    }

    // ---- calendar times ----

    /**
     * The part of systemd's calendar format refreshes use, in local time: optional weekdays, any date, then the hours
     * (one, a list, a range or *) and the minute. Several of them go with ";". {@code Mon *-*-* 08:00:00},
     * {@code Mon..Fri *-*-* 07:30}, {@code Mon..Fri *-*-* 08..18:00} (on the hour, 8 to 6),
     * {@code *-*-* 07:00; Mon..Fri *-*-* 13:30}. It works the same on Linux and macOS, so nothing asks systemd.
     */
    record Calendar(List<Rule> rules) {

        /** One part: these days, at these hours, at this minute and second. */
        record Rule(Set<DayOfWeek> days, Set<Integer> hours, int minute, int second) {
        }

        static final Pattern FORMAT = Pattern.compile(
                "(?:([A-Za-z]{3}(?:\\.\\.[A-Za-z]{3})?(?:,[A-Za-z]{3}(?:\\.\\.[A-Za-z]{3})?)*) )?\\*-\\*-\\* "
                        + "(\\*|\\d{1,2}(?:\\.\\.\\d{1,2})?(?:,\\d{1,2}(?:\\.\\.\\d{1,2})?)*):(\\d{2})(?::(\\d{2}))?");
        static final List<String> NAMES = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

        static Calendar parse(String v) {
            if (v == null || v.isBlank()) return null;
            List<Rule> rules = new ArrayList<>();
            for (String part : v.split(";")) {
                Rule r = rule(part.strip());
                if (r == null) return null;
                rules.add(r);
            }
            return rules.size() > 12 ? null : new Calendar(List.copyOf(rules));
        }

        static Rule rule(String v) {
            Matcher m = FORMAT.matcher(v);
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
            Set<Integer> hours = new java.util.TreeSet<>();
            if (m.group(2).equals("*")) {
                for (int h = 0; h < 24; h++) hours.add(h);
            } else {
                for (String part : m.group(2).split(",")) {
                    String[] range = part.split("\\.\\.");
                    int from = Integer.parseInt(range[0]), to = Integer.parseInt(range[range.length - 1]);
                    if (from > 23 || to > 23 || to < from) return null;
                    for (int h = from; h <= to; h++) hours.add(h);
                }
            }
            int min = Integer.parseInt(m.group(3));
            int s = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            if (min > 59 || s > 59) return null;
            return new Rule(days, Set.copyOf(hours), min, s);
        }

        /** The latest time at or before {@code now}. */
        ZonedDateTime previous(ZonedDateTime now) {
            ZonedDateTime best = null;
            for (Rule r : rules) {
                for (int back = 0; back <= 7; back++) {
                    LocalDate d = now.toLocalDate().minusDays(back);
                    if (!r.days().contains(d.getDayOfWeek())) continue;
                    ZonedDateTime found = null;
                    for (int h : r.hours()) {
                        ZonedDateTime t = d.atTime(h, r.minute(), r.second()).atZone(now.getZone());
                        if (!t.isAfter(now) && (found == null || t.isAfter(found))) found = t;
                    }
                    if (found != null) {
                        if (best == null || found.isAfter(best)) best = found;
                        break;
                    }
                }
            }
            return best;
        }

        /** The first time after {@code now}. */
        ZonedDateTime next(ZonedDateTime now) {
            ZonedDateTime best = null;
            for (Rule r : rules) {
                for (int ahead = 0; ahead <= 7; ahead++) {
                    LocalDate d = now.toLocalDate().plusDays(ahead);
                    if (!r.days().contains(d.getDayOfWeek())) continue;
                    ZonedDateTime found = null;
                    for (int h : r.hours()) {
                        ZonedDateTime t = d.atTime(h, r.minute(), r.second()).atZone(now.getZone());
                        if (t.isAfter(now) && (found == null || t.isBefore(found))) found = t;
                    }
                    if (found != null) {
                        if (best == null || found.isBefore(best)) best = found;
                        break;
                    }
                }
            }
            return best;
        }
    }
}
