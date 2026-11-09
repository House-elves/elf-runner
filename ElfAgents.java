import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Agents for elves: an elf whose elf.yaml says {@code agents: true} may start one from an action, by answering with
 * {@code {"agent": {id, checkout, prompt, untrusted, schema, session, readOnly, web, images}}}. The runner runs it the way it runs the desk's
 * jobs (the safety check, the sandbox, a worktree of the item, or of a repo's default branch for kind REPO), and keeps what happens in the elf's {@code agents}
 * folder as {@code <id>.json}: its state, the lines so far, its answer, the agent's commits and session. Files the
 * agent leaves go next to it as {@code <id>--<name>}. The elf's tab reads them, and so does the elf (to post an answer).
 * The agent's commits stay in the runner's worktree, which only the runner's git touches.
 */
class ElfAgents {

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    /** An elf's name for one of its slots (a session's own clone); the runner prefixes the elf's name. */
    static final Pattern SLOT = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    static final int MAX_LINES = 400;
    static final int MAX_LINE_CHARS = 600_000;
    static final int MAX_TEXT = 200_000;
    /** Images an agent is shown with its prompt (screenshots you pasted): at most this many, each at most this big. */
    static final int MAX_IMAGES = 4;
    static final int MAX_IMAGE_BYTES = 3_750_000; // 5 MB once base64, Claude's limit for one image
    /** A PDF shown with the prompt, one of the images' four. */
    static final int MAX_PDF_BYTES = 10_000_000;

    /** An elf's agent run: where it reports, and what it has said so far. */
    static final class Job {
        final long job;
        final String elf, id;
        final Path dir;
        final ObjectNode state;
        long written;
        /** The characters in its lines now. */
        long chars;
        /** It could read the web: its session isn't handed back, so no later agent resumes what it read there. */
        boolean web;

        synchronized boolean cancelled() {
            return state.path("status").asText().equals("cancelled");
        }

        Job(long job, String elf, String id, Path dir, ObjectNode state) {
            this.job = job;
            this.elf = elf;
            this.id = id;
            this.dir = dir;
            this.state = state;
        }
    }

    final ElfRunner runner;
    /** Its own range of job numbers: the desk's are its database ids, far below. */
    final AtomicLong next = new AtomicLong(1_000_000_000_000L + System.currentTimeMillis() % 1_000_000_000L * 1000);
    final Map<Long, Job> jobs = new ConcurrentHashMap<>();

    ElfAgents(ElfRunner runner) {
        this.runner = runner;
    }

    Job byJob(long job) {
        return jobs.get(job);
    }

    /**
     * Starts the agent an elf's action asked for, in the background. Returns what the action answers with in its
     * place: {id, status}.
     */
    ObjectNode request(Elves.Installed i, JsonNode req) throws IOException {
        Elves.Manifest m = i.manifest();
        if (!m.agents()) throw new IllegalArgumentException(m.name() + "'s elf.yaml doesn't say it starts agents");
        String id = req.path("id").asText("");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("An agent's id is lower case letters, digits and -");
        JsonNode checkout = req.path("checkout");
        String repo = checkout.path("repo").asText("");
        boolean whole = checkout.path("kind").asText().equals("REPO");
        // WEB: no repo at all, an empty folder of its own: an agent that only reads the web (checks a site, looks
        // something up), for steps you approved. The same rules as any agent on the web, below.
        boolean none = checkout.path("kind").asText().equals("WEB");
        if (none ? !repo.isEmpty() || checkout.path("number").asInt() != 0
                : !ElfRunner.REPO_NAME.matcher(repo).matches() || (whole ? checkout.path("number").asInt() != 0 : checkout.path("number").asInt() <= 0)
                || !List.of("PR", "ISSUE", "REPO").contains(checkout.path("kind").asText())) {
            throw new IllegalArgumentException("An agent works on one PR or issue, a repo, or only the web: checkout {repo, "
                    + "number, kind} (REPO: number 0; WEB: no repo, number 0)");
        }
        if (none && !req.path("web").asBoolean(false)) throw new IllegalArgumentException("An agent with no repo only reads the web (web)");
        // slot (REPO only): a clone of the repo of the elf's own, kept between its agents, for a session that goes on
        // over many turns. The runner names it after the elf too, so an elf only ever reaches its own.
        String slot = checkout.path("slot").asText("");
        if (!slot.isEmpty() && (!whole || !SLOT.matcher(slot).matches())) {
            throw new IllegalArgumentException("A slot is lower case letters, digits and -, on a repo (kind REPO)");
        }
        String prompt = req.path("prompt").asText("");
        // No schema: the agent answers in plain text, its last message, as {reply}. For a session's chat.
        String schema = req.path("schema").asText("");
        // goal: a session's original prompt, given again with every turn (in the system prompt), so it outlives
        // compaction. The elf's own words, from you, like the prompt.
        String goal = req.path("goal").asText("");
        if (goal.length() > MAX_TEXT) throw new IllegalArgumentException("Too long a goal");
        // agent: which coding agent works on it. Claude Code unless it says IBM Bob, which only works on a session
        // (a slot, a plain answer, no one else's text, no images): the safety check and read-only tools are Claude's.
        String agent = req.path("agent").asText("claude");
        if (!List.of("claude", "bob").contains(agent)) throw new IllegalArgumentException("An agent is claude or bob");
        if (prompt.isBlank() || prompt.length() > MAX_TEXT) throw new IllegalArgumentException("An agent needs a prompt");
        if (req.path("untrusted").asText("").length() > MAX_TEXT) throw new IllegalArgumentException("Too much text to check");
        if (!schema.isBlank()) {
            try {
                if (!ElfRunner.JSON.readTree(schema).isObject()) throw new IllegalArgumentException();
            } catch (Exception e) {
                throw new IllegalArgumentException("An agent's schema is a JSON schema");
            }
        }
        // web: it may also search and read the web (an elf working out how to do something new). What it can read and
        // send out is then only what anyone may: a public repo, and nobody else's words. Nor an earlier session's:
        // what it read then (someone's words, another checkout) would go along. Checked before anything is started.
        boolean web = req.path("web").asBoolean(false);
        String approved = req.path("approved").asText("");
        if (!approved.isBlank() && !web) throw new IllegalArgumentException("Only an agent on the web takes approved text: others take untrusted");
        if (approved.length() > MAX_TEXT) throw new IllegalArgumentException("Too much approved text");
        ArrayNode images = images(req.path("images"));
        ObjectNode env = env(req.path("env"), slot);
        ArrayNode attach = attach(req.path("attach"), m, slot);
        if (agent.equals("bob") && (slot.isEmpty() || !schema.isBlank() || req.path("readOnly").asBoolean(false)
                || req.path("web").asBoolean(false) || !req.path("untrusted").asText("").isBlank() || !images.isEmpty())) {
            throw new IllegalArgumentException("IBM Bob only works on a session: a slot, no schema, no one else's text, no images");
        }
        if (web) {
            if (!images.isEmpty()) throw new IllegalArgumentException("An agent on the web isn't shown images");
            if (!req.path("readOnly").asBoolean(false) || !(whole || none) || !req.path("untrusted").asText("").isBlank()
                    || (req.path("session").isTextual() && !req.path("session").asText().isBlank())
                    || !slot.isEmpty() || !goal.isBlank() || schema.isBlank()) {
                throw new IllegalArgumentException("An agent on the web only reads, a repo or nothing, afresh, and no one else's text");
            }
            if (!none && !isPublic(repo)) throw new IllegalArgumentException("An agent on the web only reads a public repo");
        }
        Path dir = m.data("agents");
        Files.createDirectories(dir);
        synchronized (this) {
            if (jobs.values().stream().anyMatch(j -> j.elf.equals(m.name()) && j.id.equals(id))) {
                throw new IllegalStateException("That agent is already running");
            }
        }
        long job = next.incrementAndGet();
        ObjectNode state = ElfRunner.JSON.createObjectNode().put("id", id).put("status", "queued")
                .put("created", Instant.now().toString());
        state.set("checkout", checkout.deepCopy());
        state.putArray("lines");
        Job j = new Job(job, m.name(), id, dir, state);
        jobs.put(job, j);
        write(j, true);

        ObjectNode msg = ElfRunner.JSON.createObjectNode().put("job", job).put("prompt", prompt)
                .put("untrusted", req.path("untrusted").asText("")).put("schema", schema);
        ObjectNode co = checkout.deepCopy();
        co.remove("web"); // the runner's own mark (a web agent's clone), never the elf's to set
        co.remove("slot");
        if (!slot.isEmpty()) co.put("slot", m.name() + "_" + slot);
        msg.set("checkout", co);
        if (!goal.isBlank()) msg.put("goal", goal);
        if (agent.equals("bob")) msg.put("agent", "bob");
        if (req.path("session").isTextual() && !req.path("session").asText().isBlank()) msg.put("session", req.path("session").asText());
        if (!images.isEmpty()) msg.set("images", images);
        if (!env.isEmpty()) msg.set("env", env);
        if (!attach.isEmpty()) msg.set("attach", attach);
        // readOnly: it only reads the checkout (Read, Grep, Glob): no shell, web or MCP, so nothing it reads can leave.
        if (req.path("readOnly").asBoolean(false)) msg.put("readOnly", true);
        // approved (web only): text you approved on your screen but that came from someone's words (steps written
        // from an email, say). The runner puts it in the prompt itself, so what's screened as someone's words (with
        // the strict check) is exactly what the agent gets.
        if (web) {
            msg.put("web", true);
            j.web = true;
            if (!approved.isBlank()) {
                prompt = prompt + "\n\n<approved>\n" + approved.replace("</", "<\\/") + "\n</approved>";
                msg.put("prompt", prompt);
                msg.put("approved", approved);
            }
            // Its whole prompt and answer's shape go through the safety check: an elf that put someone's words there,
            // not in untrusted, still doesn't hand an injection to an agent that can reach the web.
            msg.put("untrusted", prompt + "\n\n" + schema);
            msg.put("task", true);
        }
        Thread.ofVirtual().start(() -> {
            try {
                runner.start(msg);
            } finally {
                jobs.remove(job);
                ended(j);
            }
        });
        return ElfRunner.JSON.createObjectNode().put("id", id).put("status", "queued");
    }

    static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]{0,63}");
    /** Names the sandbox sets itself, or that change how a shell, git or the agent runs: never a session's. */
    static final Pattern ENV_TAKEN = Pattern.compile("(HOME|USER|LOGNAME|SHELL|PATH|PWD|OLDPWD|TMPDIR|TERM|LANG|IFS|ENV|BASH_ENV|"
            + "PROMPT_COMMAND|SHELLOPTS|BASHOPTS|PS4|JAVA_HOME|XDG_.*|LD_.*|DYLD_.*|GIT_.*|GH_.*|CLAUDE_.*|BOB_.*|ELF_.*|SESSION_ENV_FILE|"
            + "DOCKER_HOST|CONTAINER_HOST|CONTAINERS_CONF|TESTCONTAINERS_.*|DISABLE_AUTOUPDATER)");

    /**
     * A session's variables, checked (env: {NAME: value}): up to 30, for the commands its agent runs (see
     * ElfRunner.envExtra), only on a session (a slot). Their values are never logged or kept.
     */
    /** How big a file given to a session's agent (attach) may be, and how many with one message. */
    static final long MAX_ATTACH = 50L * 1024 * 1024;
    static final int MAX_ATTACHED = 10;
    static final java.util.regex.Pattern ATTACH_NAME = java.util.regex.Pattern.compile("[^/\\\\\\p{Cntrl}]{1,120}");

    /**
     * Files of the elf's own a session's agent is given (a slide deck you dropped on the message): [{folder, file,
     * name}], each a file in one of its data folders, copied into its clone's .attached/ folder as name before it starts.
     * Only to a session (a slot), whose clone is the elf's alone.
     */
    static ArrayNode attach(JsonNode given, Elves.Manifest m, String slot) throws java.io.IOException {
        ArrayNode out = ElfRunner.JSON.createArrayNode();
        if (given.isMissingNode() || given.isNull()) return out;
        if (!given.isArray() || given.size() > MAX_ATTACHED) throw new IllegalArgumentException("attach is up to " + MAX_ATTACHED + " files");
        if (!given.isEmpty() && slot.isEmpty()) throw new IllegalArgumentException("Only a session (a slot) is given files (attach)");
        for (JsonNode f : given) {
            String folder = f.path("folder").asText(""), file = f.path("file").asText(""), name = f.path("name").asText("").strip();
            if (!Elves.FILE.matcher(file).matches() || folder.equals("agents")) throw new IllegalArgumentException("Not a file of the elf's: " + file);
            if (!ATTACH_NAME.matcher(name).matches() || name.startsWith(".")) throw new IllegalArgumentException("Not a file name: " + name);
            Path dir = m.data(folder), p = dir.resolve(file);
            if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !p.toRealPath().startsWith(dir.toRealPath())) {
                throw new IllegalArgumentException("No such file: " + file);
            }
            if (Files.size(p) > MAX_ATTACH) throw new IllegalArgumentException(name + " is bigger than 50 MB");
            out.addObject().put("from", p.toRealPath().toString()).put("name", name);
        }
        return out;
    }

    static ObjectNode env(JsonNode given, String slot) {
        ObjectNode out = ElfRunner.JSON.createObjectNode();
        if (given.isMissingNode() || given.isNull()) return out;
        if (!given.isObject() || given.size() > 30) throw new IllegalArgumentException("env is up to 30 variables, {NAME: value}");
        if (!given.isEmpty() && slot.isEmpty()) throw new IllegalArgumentException("Only a session (a slot) is given variables (env)");
        for (var e : given.properties()) {
            String name = e.getKey();
            if (!ENV_NAME.matcher(name).matches() || ENV_TAKEN.matcher(name).matches()) {
                throw new IllegalArgumentException(name + " can't be a session's variable");
            }
            String value = e.getValue().isTextual() ? e.getValue().asText() : null;
            if (value == null || value.length() > 8192 || value.chars().anyMatch(c -> c == 0 || c == '\n' || c == '\r')) {
                throw new IllegalArgumentException(name + "'s value is text on one line, up to 8192 characters");
            }
            out.put(name, value);
        }
        return out;
    }

    /**
     * The images an agent is shown with its prompt, checked: {type, data} with data base64, and the bytes really an
     * image of that type (a PNG, JPEG, GIF or WebP), no bigger than Claude takes.
     */
    static ArrayNode images(JsonNode given) {
        ArrayNode out = ElfRunner.JSON.createArrayNode();
        if (given.isMissingNode() || given.isNull()) return out;
        if (!given.isArray() || given.size() > MAX_IMAGES) throw new IllegalArgumentException("An agent is shown at most " + MAX_IMAGES + " images");
        for (JsonNode i : given) {
            String type = i.path("type").asText("");
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(i.path("data").asText(""));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("An image's data is base64");
            }
            // A PDF (runner 0.50.0): Claude reads it whole, its text and its pictures, as a document with the prompt.
            if (type.equals("application/pdf")) {
                if (bytes.length == 0 || bytes.length > MAX_PDF_BYTES) throw new IllegalArgumentException("A PDF is at most 10 MB");
                if (!starts(bytes, 0, '%', 'P', 'D', 'F', '-')) throw new IllegalArgumentException("Not a PDF, or not the type it says");
                out.addObject().put("type", type).put("data", Base64.getEncoder().encodeToString(bytes));
                continue;
            }
            if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("An image is at most 3.75 MB");
            if (!type.equals(imageType(bytes))) throw new IllegalArgumentException("Not a PNG, JPEG, GIF or WebP image, or not the type it says");
            out.addObject().put("type", type).put("data", Base64.getEncoder().encodeToString(bytes));
        }
        return out;
    }

    /** What the bytes are, by how they start: image/png, image/jpeg, image/gif, image/webp, or null. */
    static String imageType(byte[] b) {
        if (starts(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (starts(b, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 0, 'G', 'I', 'F', '8')) return "image/gif";
        if (starts(b, 0, 'R', 'I', 'F', 'F') && starts(b, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        return null;
    }

    static boolean starts(byte[] b, int at, int... magic) {
        if (b.length < at + magic.length) return false;
        for (int k = 0; k < magic.length; k++) if ((b[at + k] & 0xFF) != magic[k]) return false;
        return true;
    }

    /** The repo is public: GitHub shows it to anyone (without a token, a private one is a 404). */
    static boolean isPublic(String repo) {
        try {
            var r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("https://api.github.com/repos/" + repo)).timeout(java.time.Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "elf-runner").build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && !ElfRunner.JSON.readTree(r.body()).path("private").asBoolean(true);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stops one of an elf's agents: Claude if it's running, or before it starts. It stays known until its run ends,
     * so nothing it says after reaches the desk as a job of the desk's.
     */
    void cancel(String elf, String id) {
        for (Job j : jobs.values()) {
            if (j.elf.equals(elf) && j.id.equals(id)) {
                synchronized (j) {
                    if (!List.of("queued", "running").contains(j.state.path("status").asText())) return;
                    j.state.put("status", "cancelled").put("finished", Instant.now().toString());
                }
                write(j, true);
                runner.cancel(j.job);
                ElfRunner.log(elf + "'s agent " + id + " stopped");
                return;
            }
        }
        throw new IllegalArgumentException("That agent isn't running");
    }

    /**
     * What you say to one of an elf's running agents (a session's, in plain text), read at its next step: answers
     * {said: true}, or {said: false, why} when it isn't working any more (its answer came first), to send as the next turn.
     */
    ObjectNode say(String elf, JsonNode req) throws IOException {
        String id = req.path("agent").asText(""), text = req.path("text").asText("");
        if (text.isBlank() || text.length() > 20_000) throw new IllegalArgumentException("Say something (at most 20000 characters)");
        ObjectNode out = ElfRunner.JSON.createObjectNode();
        for (Job j : jobs.values()) {
            if (j.elf.equals(elf) && j.id.equals(id)) {
                boolean working;
                synchronized (j) {
                    working = j.state.path("status").asText().equals("running");
                }
                if (working && runner.say(j.job, text)) return out.put("said", true);
                return out.put("said", false).put("why", "It had just answered");
            }
        }
        return out.put("said", false).put("why", "It isn't working any more");
    }

    void started(Job j) {
        synchronized (j) {
            if (j.cancelled()) return;
            j.state.put("status", "running").put("started", Instant.now().toString());
        }
        write(j, true);
    }

    void line(Job j, String text) {
        synchronized (j) {
            if (j.cancelled()) return;
            if (j.state.path("status").asText().equals("queued")) j.state.put("status", "running").put("started", Instant.now().toString());
            var lines = j.state.withArray("lines");
            lines.add(text);
            j.chars += text.length();
            // At most MAX_LINES, and few enough characters that the tab can read the report (the desk reads 1.5 MB).
            while (lines.size() > MAX_LINES || (j.chars > MAX_LINE_CHARS && lines.size() > 1)) j.chars -= lines.remove(0).asText().length();
        }
        write(j, false);
    }

    void failed(Job j, String error) {
        failed(j, error, null);
    }

    /** @param cost what it cost before it failed, or null */
    void failed(Job j, String error, Double cost) {
        synchronized (j) {
            if (j.state.path("status").asText().equals("cancelled")) return;
            j.state.put("status", "failed").put("error", error).put("finished", Instant.now().toString());
            if (cost != null) j.state.put("cost", cost);
        }
        write(j, true);
    }

    /**
     * Its answer: the structured output, the session (to follow up) and the commits. Not the worktree: a later agent
     * on the same item writes there, so the elf never runs git in it; pushing goes through the runner (gitPush).
     */
    void done(Job j, ObjectNode done) {
        synchronized (j) {
            if (j.cancelled()) return;
            j.state.put("status", "done").put("finished", Instant.now().toString());
            j.state.set("output", done.path("output"));
            if (done.path("session").isTextual() && !j.web) j.state.put("session", done.path("session").asText());
            if (done.has("git")) j.state.set("git", done.path("git"));
            if (done.has("cost")) j.state.set("cost", done.path("cost"));
            if (done.has("bobcoins")) j.state.set("bobcoins", done.path("bobcoins"));
            for (String k : List.of("model", "durationMs", "context")) if (done.has(k)) j.state.set(k, done.path(k));
        }
        write(j, true);
    }

    /** The agent's files for you, next to its answer as {@code <id>--<name>}. */
    void files(Job j, List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path f : files) {
            String name = j.id + "--" + f.getFileName();
            if (!Elves.FILE.matcher(name).matches()) continue;
            try {
                Files.copy(f, j.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                names.add(name);
            } catch (IOException e) {
                line(j, "Couldn't keep " + f.getFileName() + ": " + e.getMessage());
            }
        }
        synchronized (j) {
            names.forEach(j.state.withArray("files")::add);
        }
        write(j, true);
    }

    /**
     * Writes {@code <id>.json} (whole, then moved into place, so a reader never sees half of it) and tells the desk,
     * which tells the elf's open tab. While lines stream in, at most every half second.
     */
    void write(Job j, boolean now) {
        long t = System.currentTimeMillis();
        String text;
        synchronized (j) {
            if (!now && t - j.written < 500) {
                scheduleFlush(j);
                return;
            }
            j.written = t;
            text = j.state.toString();
        }
        try {
            Path tmp = j.dir.resolve("." + j.id + ".json.tmp");
            Files.writeString(tmp, text);
            Files.move(tmp, j.dir.resolve(j.id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            ElfRunner.log("Couldn't write " + j.elf + "'s agent " + j.id + ": " + e.getMessage());
        }
        runner.send(ElfRunner.JSON.createObjectNode().put("type", "elf.changed").put("elf", j.elf).put("what", "agents"));
    }

    /**
     * When one of its agents has ended (done, failed or stopped): the elf's agents.done action, with {id}, so the
     * elf can take the answer in (a thread's reply, a draft). It may answer with the next agent to start, as its other
     * actions can (a message you sent while this one worked). Then its tab hears "agents" again.
     */
    void ended(Job j) {
        try {
            Elves.Installed i = runner.elves.get(j.elf);
            String action = i.manifest().agentDone();
            if (action == null) return;
            Elves.Action a = i.manifest().actions().get(action);
            String last = runner.runElf(j.elf, a.args(), ElfRunner.JSON.createObjectNode().put("id", j.id).toString());
            JsonNode answer;
            try {
                answer = ElfRunner.JSON.readTree(last);
            } catch (Exception e) {
                answer = null;
            }
            if (answer instanceof ObjectNode o && o.path("agent").isObject()) request(i, o.path("agent"));
            runner.send(ElfRunner.JSON.createObjectNode().put("type", "elf.changed").put("elf", j.elf).put("what", "agents"));
        } catch (Exception e) {
            ElfRunner.log(j.elf + " couldn't take in its agent " + j.id + ": " + ElfRunner.rootMessage(e));
        }
    }

    final Map<Long, Boolean> flushing = new ConcurrentHashMap<>();

    void scheduleFlush(Job j) {
        if (flushing.putIfAbsent(j.job, true) != null) return;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(550);
            } catch (InterruptedException ignored) {
            }
            flushing.remove(j.job);
            write(j, true);
        });
    }
}
