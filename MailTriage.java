///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java
//SOURCES EmailTriage.java
//SOURCES Reply.java
//SOURCES Style.java
//SOURCES Research.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Sorts your unread Gmail with Claude: noise is marked read and labelled, the rest stays unread and is saved with the
 * reason, for its Mail tab on Elf Desk. Sends nothing.
 */
@Command(name = "mail-triage", mixinStandardHelpOptions = true, version = "1.5.0",
        description = "Sorts your unread Gmail with Claude, for Elf Desk's Mail tab")
public class MailTriage implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Decide, but mark and label nothing")
    boolean dryRun;

    @Option(names = "--action", paramLabel = "NAME", description = "For the Mail tab: done (stdin: {account, messageId}) "
            + "marks the thread read in Gmail and records it in ~/.config/mail-triage/done; draft-reply ({account, "
            + "messageId}) suggests a reply; send-reply (the same, and content, all, to) sends it; find-out (the same, "
            + "content and repo) has an agent look up what the reply leaves open. Prints one JSON line.")
    String action;

    static final Path RUNS = Config.CONFIG_DIR.resolve("runs");
    static final Path DONE = Config.CONFIG_DIR.resolve("done");
    static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Integer call() {
        long startTime = System.currentTimeMillis();
        if (!waitForNetwork()) {
            System.err.println("No network: couldn't reach Gmail.");
            return 1;
        }
        Config config = Config.load();
        if (action != null) return action(config);

        List<Config.GmailAccount> accounts = config.getGmailAccounts();
        if (accounts.isEmpty()) {
            System.err.println("No Gmail account set up: set Gmail account and Gmail app password in its settings.");
            return 1;
        }
        List<Map<String, Object>> triageAccounts = new ArrayList<>();
        for (Config.GmailAccount account : accounts) {
            EmailTriage.AccountResult result = EmailTriage.processAccount(account, config.maxEmails, config.triageLabel, dryRun);
            Map<String, Object> accountData = new LinkedHashMap<>();
            accountData.put("email", account.email());
            accountData.put("processed", result.results().size());
            accountData.put("markedRead", result.results().stream().filter(r -> "MARK_READ".equals(r.decision())).count());
            accountData.put("keptUnread", result.results().stream().filter(r -> "KEEP_UNREAD".equals(r.decision())).count());
            List<Map<String, String>> decisions = new ArrayList<>();
            for (EmailTriage.TriageResult r : result.results()) {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("subject", r.subject());
                d.put("from", r.from());
                d.put("decision", r.decision());
                d.put("reason", r.reason());
                d.put("messageId", r.messageId());
                decisions.add(d);
            }
            accountData.put("decisions", decisions);
            triageAccounts.add(accountData);
        }

        Map<String, Object> runData = new LinkedHashMap<>();
        runData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        runData.put("dryRun", dryRun);
        runData.put("triage", Map.of("accounts", triageAccounts));
        runData.put("durationMs", System.currentTimeMillis() - startTime);
        runData.put("aiCost", EmailTriage.AI_COST.sum());
        runData.put("status", "success");
        if (dryRun) {
            System.out.println("Dry run: nothing was marked or labelled, and the run isn't saved.");
        } else {
            save(runData);
        }
        System.out.println("Done!");
        return 0;
    }

    /** --action done: the Mail tab's Done, its values on stdin (the runner checked them against elf.yaml). */
    private int action(Config config) {
        if (action.equals("learn-style") || action.equals("save-style")) return style(config);
        if (!List.of("done", "draft-reply", "send-reply", "find-out").contains(action)) {
            System.err.println("Not an action: " + action);
            return 2;
        }
        JsonNode in;
        try {
            in = JSON.readTree(System.in);
        } catch (Exception e) {
            System.err.println("Can't read what to do: " + e.getMessage());
            return 2;
        }
        String accountName = in.path("account").asText(""), messageId = in.path("messageId").asText("");
        Config.GmailAccount account = config.getGmailAccounts().stream()
                .filter(a -> a.email().equalsIgnoreCase(accountName)).findFirst().orElse(null);
        if (account == null) {
            System.err.println("Not a configured Gmail account: " + accountName);
            return 2;
        }
        if (!action.equals("done")) return reply(account, in);
        int marked;
        try {
            marked = EmailTriage.markThreadRead(account, messageId);
        } catch (Exception e) {
            System.err.println("Couldn't mark it read: " + e.getMessage());
            return 1;
        }
        if (recordDone(account.email(), messageId) != 0) return 1;
        System.out.println("{\"marked\": " + marked + "}");
        return 0;
    }

    /** learn-style (from your Sent folders) and save-style ({text}): how you write, for suggested replies. */
    private int style(Config config) {
        try {
            ObjectNode out = action.equals("learn-style")
                    ? Style.learn(Reply.yourEmails(config.getGmailAccounts()), "sent emails")
                    : Style.save(JSON.readTree(System.in).path("text").asText(""), "you");
            System.out.println(JSON.writeValueAsString(out));
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println((action.equals("learn-style") ? "Couldn't learn how you write: " : "Couldn't save it: ") + e.getMessage());
            return 1;
        }
    }

    /** draft-reply and send-reply: the email is read from Gmail each time, so the reply goes where the email says. */
    private int reply(Config.GmailAccount account, JsonNode in) {
        try {
            Reply.Email e = Reply.find(account, in.path("messageId").asText(""));
            if (action.equals("draft-reply")) {
                System.out.println(JSON.writeValueAsString(Reply.draft(e)));
                return 0;
            }
            if (action.equals("find-out")) {
                // An agent, which the runner starts: it looks up what the reply leaves open, in the repo you name (or, for
                // "mine", the one of yours Claude picks).
                System.out.println(JSON.writeValueAsString(Research.request(account.email(), "an email", Reply.json(e),
                        in.path("content").asText(""), in.path("repo").asText(""))));
                return 0;
            }
            // The confirm bar named everyone it goes to (Cc too, for Reply all): it must be who the email says now.
            boolean all = in.path("all").asBoolean(false);
            if (!e.recipients(all).equalsIgnoreCase(in.path("to").asText(""))) {
                throw new IllegalArgumentException("This reply would go to " + e.recipients(all) + " now, not "
                        + in.path("to").asText("") + ". Suggest the reply again.");
            }
            System.out.println(JSON.writeValueAsString(Reply.send(e, in.path("content").asText(""), all)));
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println((action.equals("draft-reply") ? "Couldn't suggest a reply: " : action.equals("find-out")
                    ? "Couldn't start the agent: " : "Couldn't send it: ") + e.getMessage());
            return 1;
        }
    }

    /** done/mail.json: {account: {Message-ID: when}}, a month of it. */
    private static int recordDone(String account, String messageId) {
        Path file = DONE.resolve("mail.json");
        try {
            Files.createDirectories(DONE);
            ObjectNode all = Files.exists(file) ? (ObjectNode) JSON.readTree(file.toFile()) : JSON.createObjectNode();
            String monthAgo = Instant.now().minus(Duration.ofDays(31)).toString();
            all.fields().forEachRemaining(g -> {
                if (g.getValue() instanceof ObjectNode o) o.properties().removeIf(e -> e.getValue().asText().compareTo(monthAgo) < 0);
            });
            all.withObject(account).put(messageId, Instant.now().toString());
            Path tmp = file.resolveSibling("mail.json.new");
            Files.writeString(tmp, JSON.writeValueAsString(all));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return 0;
        } catch (Exception e) {
            System.err.println("Marked read, but couldn't note it as done: " + e.getMessage());
            return 1;
        }
    }

    private static void save(Map<String, Object> runData) {
        try {
            Files.createDirectories(RUNS);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(RUNS.resolve(timestamp + ".json").toFile(), runData);
            prune(90);
        } catch (IOException e) {
            System.err.println("Failed to save the run: " + e.getMessage());
        }
    }

    private static void prune(int maxDays) {
        Instant cutoff = Instant.now().minus(maxDays, ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(RUNS)) {
            files.filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** A few tries over a minute: a refresh right after the laptop wakes may beat the Wi-Fi. */
    private static boolean waitForNetwork() {
        for (int attempt = 0; attempt < 4; attempt++) {
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                client.send(HttpRequest.newBuilder().uri(URI.create("https://mail.google.com"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                return true;
            } catch (Exception e) {
                try {
                    TimeUnit.SECONDS.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new MailTriage()).execute(args));
    }
}
