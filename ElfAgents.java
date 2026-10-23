import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Agents for elves: an elf whose elf.yaml says {@code agents: true} may start one from an action, by answering with
 * {@code {"agent": {id, checkout, prompt, untrusted, schema, session, readOnly, web}}}. The runner runs it the way it runs the desk's
 * jobs (the safety check, the sandbox, a worktree of the item, or of a repo's default branch for kind REPO), and keeps what happens in the elf's {@code agents}
 * folder as {@code <id>.json}: its state, the lines so far, its answer, the agent's commits and session. Files the
 * agent leaves go next to it as {@code <id>--<name>}. The elf's tab reads them, and so does the elf (to post an answer).
 * The agent's commits stay in the runner's worktree, which only the runner's git touches.
 */
class ElfAgents {

    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    static final int MAX_LINES = 400;
    static final int MAX_TEXT = 200_000;

    /** An elf's agent run: where it reports, and what it has said so far. */
    static final class Job {
        final long job;
        final String elf, id;
        final Path dir;
        final ObjectNode state;
        long written;
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
        if (!ElfRunner.REPO_NAME.matcher(repo).matches() || (whole ? checkout.path("number").asInt() != 0 : checkout.path("number").asInt() <= 0)
                || !List.of("PR", "ISSUE", "REPO").contains(checkout.path("kind").asText())) {
            throw new IllegalArgumentException("An agent works on one PR or issue, or a repo: checkout {repo, number, kind} (REPO: number 0)");
        }
        String prompt = req.path("prompt").asText("");
        String schema = req.path("schema").asText("");
        if (prompt.isBlank() || prompt.length() > MAX_TEXT) throw new IllegalArgumentException("An agent needs a prompt");
        if (req.path("untrusted").asText("").length() > MAX_TEXT) throw new IllegalArgumentException("Too much text to check");
        try {
            if (!ElfRunner.JSON.readTree(schema).isObject()) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalArgumentException("An agent's schema is a JSON schema");
        }
        // web: it may also search and read the web (an elf working out how to do something new). What it can read and
        // send out is then only what anyone may: a public repo, and nobody else's words. Nor an earlier session's:
        // what it read then (someone's words, another checkout) would go along. Checked before anything is started.
        boolean web = req.path("web").asBoolean(false);
        if (web) {
            if (!req.path("readOnly").asBoolean(false) || !whole || !req.path("untrusted").asText("").isBlank()
                    || (req.path("session").isTextual() && !req.path("session").asText().isBlank())) {
                throw new IllegalArgumentException("An agent on the web only reads, a repo, afresh, and no one else's text");
            }
            if (!isPublic(repo)) throw new IllegalArgumentException("An agent on the web only reads a public repo");
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
        msg.set("checkout", co);
        if (req.path("session").isTextual() && !req.path("session").asText().isBlank()) msg.put("session", req.path("session").asText());
        // readOnly: it only reads the checkout (Read, Grep, Glob): no shell, web or MCP, so nothing it reads can leave.
        if (req.path("readOnly").asBoolean(false)) msg.put("readOnly", true);
        if (web) {
            msg.put("web", true);
            j.web = true;
            // Its whole prompt and answer's shape go through the safety check: an elf that put someone's words there,
            // not in untrusted, still doesn't hand an injection to an agent that can reach the web.
            msg.put("untrusted", prompt + "\n\n" + schema);
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
            while (lines.size() > MAX_LINES) lines.remove(0);
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
     * elf can take the answer in (a thread's reply, a draft). Then its tab hears "agents" again.
     */
    void ended(Job j) {
        try {
            Elves.Installed i = runner.elves.get(j.elf);
            String action = i.manifest().agentDone();
            if (action == null) return;
            Elves.Action a = i.manifest().actions().get(action);
            runner.runElf(j.elf, a.args(), ElfRunner.JSON.createObjectNode().put("id", j.id).toString());
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
