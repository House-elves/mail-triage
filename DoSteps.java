import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Do it, from Look into: an agent carries out the steps you ticked (as you left them), reading only the web. It never
 * sees the email: someone else's words don't reach an agent that can reach the web, only the steps you approved on
 * your screen. It has no logins and changes nothing; a step that needs you (signing in, changing a setting, paying)
 * comes back as yours.
 */
public class DoSteps {

    static final int MAX_STEPS = 10, MAX_STEP = 400;

    /** {note, summary, results: [{step, status, found, source}]}. */
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"note\":{\"type\":\"string\",\"description\":\"Anything you say to the user that isn't the report.\"},"
            + "\"summary\":{\"type\":\"string\",\"description\":\"One or two sentences: what you found, and what's left for them.\"},"
            + "\"results\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"step\":{\"type\":\"string\",\"description\":\"The step, as given.\"},"
            + "\"status\":{\"type\":\"string\",\"enum\":[\"done\",\"needs-you\",\"couldnt\"],\"description\":\"done: you did "
            + "or found it out; needs-you: it needs them (a sign-in, a change, a decision); couldnt: you tried and couldn't.\"},"
            + "\"found\":{\"type\":\"string\",\"description\":\"What you found or did, or why it needs them, in a sentence or two.\"},"
            + "\"source\":{\"type\":\"string\",\"description\":\"The URL you found it at, or empty.\"}},"
            + "\"required\":[\"step\",\"status\",\"found\",\"source\"]}}},"
            + "\"required\":[\"note\",\"summary\",\"results\"]}";

    /** {"agent": ...}: the runner starts it, and answers with its {id, status} in its place. */
    static ObjectNode request(String steps) {
        List<String> list = new ArrayList<>();
        for (String s : (steps == null ? "" : steps).split("\\R")) {
            String t = s.strip();
            if (t.isEmpty()) continue;
            if (t.length() > MAX_STEP) throw new IllegalArgumentException("A step is at most " + MAX_STEP + " characters");
            list.add(t);
        }
        if (list.isEmpty()) throw new IllegalArgumentException("Tick at least one step to do");
        if (list.size() > MAX_STEPS) throw new IllegalArgumentException("At most " + MAX_STEPS + " steps at a time");
        String version = System.getenv("ELF_RUNNER_VERSION");
        if (version == null || older(version, "0.45.2")) {
            throw new IllegalArgumentException("Do it needs runner 0.45.2 or later: update Elf Desk and the runner first");
        }
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < list.size(); i++) numbered.append(i + 1).append(". ").append(list.get(i)).append('\n');
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH));
        String prompt = "Someone asked you to carry out these steps for them, about an email they got. Today is " + today
                + ". You can only search and read the web (WebSearch, WebFetch): you can't sign in anywhere, change "
                + "anything, send anything or pay for anything, and you mustn't try. Do each step you can that way: check "
                + "a site, look something up, find out what a status or an error means. A step that needs them (signing "
                + "in, changing a setting, deciding, replying) is theirs: say so, and what they'd need to do. Don't "
                + "guess: say what you saw, and where. Keep it brief. The steps are below, in <approved>.\n\n"
                + "Answer with a result for each step, in order, and a summary.";
        ObjectNode agent = Reply.JSON.createObjectNode().put("id", "do-" + System.currentTimeMillis());
        agent.putObject("checkout").put("repo", "").put("number", 0).put("kind", "WEB");
        // The steps go as approved: the runner puts them in the prompt itself, and screens them as someone's words
        // (they were written from an email), as well as the task as a whole.
        agent.put("prompt", prompt).put("approved", numbered.toString()).put("schema", SCHEMA).put("readOnly", true).put("web", true);
        ObjectNode out = Reply.JSON.createObjectNode();
        out.set("agent", agent);
        return out;
    }

    /** {@code version} is older than {@code than}: both x.y.z. */
    static boolean older(String version, String than) {
        String[] a = version.strip().split("\\."), b = than.split("\\.");
        for (int i = 0; i < 3; i++) {
            int x = i < a.length && a[i].matches("\\d{1,6}") ? Integer.parseInt(a[i]) : 0, y = Integer.parseInt(b[i]);
            if (x != y) return x < y;
        }
        return false;
    }
}
