import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Outlook through Microsoft Graph, for an account signed in with a return address ({@link Config.Kind#GRAPH}): what
 * the IMAP and SMTP paths do elsewhere, the way Evolution reads an organisation's mail where IMAP isn't allowed. Only
 * your own mailbox (/me): its inbox to read and mark, its Sent Items to learn how you write, and sending your reply.
 */
public class Graph {

    /** Not final: a test answers in Microsoft's place. */
    static String base = "https://graph.microsoft.com/v1.0";

    /** What an email in the inbox is read with. The body comes as text (see {@link #call}). */
    static final String FIELDS = "id,internetMessageId,conversationId,subject,from,replyTo,toRecipients,ccRecipients,sentDateTime,"
            + "receivedDateTime,isRead,isDraft,body,internetMessageHeaders";

    // ---- the triage ----

    /** The account's unread inbox emails, the newest {@code max}, oldest first as the IMAP path has them. */
    static List<EmailTriage.EmailInfo> unread(Config.Account a, int max) throws IOException, InterruptedException {
        // Graph sorts a filtered list only by what the filter names first, so it names the date it sorts by.
        JsonNode r = call(a, "GET", "/me/mailFolders/inbox/messages" + query("$filter", "receivedDateTime ge 1900-01-01T00:00:00Z and isRead eq false",
                "$orderby", "receivedDateTime desc", "$top", String.valueOf(max), "$select", FIELDS), null);
        List<EmailTriage.EmailInfo> emails = new ArrayList<>();
        for (JsonNode m : r.path("value")) {
            Map<String, String> h = headers(m);
            String body = m.path("body").path("content").asText("");
            emails.add(0, new EmailTriage.EmailInfo(m.path("id").asText(), address(m.path("from")), addresses(m.path("toRecipients")),
                    addresses(m.path("ccRecipients")), m.path("subject").asText(""), m.path("receivedDateTime").asText(""),
                    h.getOrDefault("list-id", ""), body.length() > 2000 ? body.substring(0, 2000) + "..." : body,
                    m.path("internetMessageId").asText(""), h.getOrDefault("in-reply-to", ""), h.getOrDefault("references", ""),
                    m.path("conversationId").asText("")));
        }
        return emails;
    }

    /** Marks these emails (Graph's IDs) read, 20 a request. Answers how many were. */
    static int markRead(Config.Account a, List<String> ids) throws IOException, InterruptedException {
        int marked = 0;
        for (int i = 0; i < ids.size(); i += 20) {
            ObjectNode batch = SignIn.JSON.createObjectNode();
            ArrayNode requests = batch.putArray("requests");
            List<String> chunk = ids.subList(i, Math.min(i + 20, ids.size()));
            for (int j = 0; j < chunk.size(); j++) {
                ObjectNode req = requests.addObject().put("id", String.valueOf(j)).put("method", "PATCH").put("url", "/me/messages/" + enc(chunk.get(j)));
                req.putObject("headers").put("Content-Type", "application/json");
                req.putObject("body").put("isRead", true);
            }
            JsonNode r = call(a, "POST", "/$batch", SignIn.JSON.writeValueAsString(batch));
            for (JsonNode res : r.path("responses")) {
                if (res.path("status").asInt() / 100 == 2) marked++;
                else System.err.println("  Couldn't mark one read: " + res.path("body").path("error").path("message").asText("HTTP " + res.path("status").asInt()));
            }
        }
        return marked;
    }

    /**
     * Done: marks the inbox thread of the email with this Message-ID read (its Outlook conversation), as {@link
     * EmailTriage#markThreadRead} does at IMAP. Answers how many were marked, 0 when it isn't in the inbox.
     */
    static int markThreadRead(Config.Account a, String id) throws IOException, InterruptedException {
        JsonNode m = inInbox(a, id, "id,conversationId");
        if (m == null) return 0;
        JsonNode r = call(a, "GET", "/me/mailFolders/inbox/messages" + query("$filter", "conversationId eq " + literal(m.path("conversationId").asText())
                + " and isRead eq false", "$select", "id", "$top", "200"), null);
        List<String> ids = new ArrayList<>();
        for (JsonNode x : r.path("value")) ids.add(x.path("id").asText());
        return ids.isEmpty() ? 0 : markRead(a, ids);
    }

    /** The inbox email with this Message-ID (no brackets), or null; refuses two, since a sender can copy one. */
    private static JsonNode inInbox(Config.Account a, String id, String select) throws IOException, InterruptedException {
        JsonNode r = call(a, "GET", "/me/mailFolders/inbox/messages" + query("$filter", "internetMessageId eq " + literal("<" + id + ">"),
                "$select", select, "$top", "2"), null);
        if (r.path("value").size() > 1) throw new IllegalArgumentException(EmailTriage.SAME_ID);
        return r.path("value").isEmpty() ? null : r.path("value").get(0);
    }

    // ---- a reply ----

    /** {@link Reply#find} for Graph: the email, who a reply goes to, and its conversation for context. */
    static Reply.Email find(Config.Account a, String id) throws Exception {
        JsonNode m = inInbox(a, id, FIELDS);
        if (m == null) throw new IllegalArgumentException("That email isn't in the inbox any more");
        String me = a.email();
        JsonNode replyTo = m.path("replyTo");
        InternetAddress to = internet(replyTo.isArray() && !replyTo.isEmpty() ? replyTo.get(0) : m.path("from"));
        if (to == null) throw new IllegalArgumentException("That email doesn't say who sent it, so there's no one to reply to");
        List<InternetAddress> others = new ArrayList<>();
        for (JsonNode r : iterable(m.path("toRecipients"), m.path("ccRecipients"))) {
            InternetAddress ia = internet(r);
            if (ia != null && !ia.getAddress().equalsIgnoreCase(me) && !ia.getAddress().equalsIgnoreCase(to.getAddress())
                    && others.stream().noneMatch(o -> o.getAddress().equalsIgnoreCase(ia.getAddress()))) {
                others.add(ia);
            }
        }
        // Its conversation in every folder (your replies are in Sent Items), as Reply#find has it: only what everyone
        // the reply can go to already saw, nothing after the email, then the email itself last.
        List<String> readers = new ArrayList<>(List.of(to.getAddress()));
        others.forEach(o -> readers.add(o.getAddress()));
        JsonNode conv = call(a, "GET", "/me/messages" + query("$filter", "conversationId eq " + literal(m.path("conversationId").asText()),
                "$select", "id,from,toRecipients,ccRecipients,sentDateTime,isDraft,body", "$top", "50"), null);
        Instant at = instant(m.path("sentDateTime"));
        List<JsonNode> theirs = new ArrayList<>();
        for (JsonNode t : conv.path("value")) {
            if (t.path("id").asText().equals(m.path("id").asText()) || t.path("isDraft").asBoolean(false)) continue;
            Instant when = instant(t.path("sentDateTime"));
            if (at != null && when != null && when.isAfter(at)) continue;
            if (involvesAll(t, readers)) theirs.add(t);
        }
        theirs.sort(Comparator.comparing(t -> instant(t.path("sentDateTime")), Comparator.nullsFirst(Comparator.naturalOrder())));
        theirs.add(m);
        List<Map<String, String>> context = new ArrayList<>();
        for (JsonNode t : theirs.subList(Math.max(0, theirs.size() - Reply.READ_BACK), theirs.size())) {
            context.add(Map.of("from", from(t), "date", Reply.date(date(t)), "body", Reply.clip(t.path("body").path("content").asText(""))));
        }
        return new Reply.Email(a, "<" + id + ">", m.path("subject").asText(""), headers(m).getOrDefault("references", ""), to, others,
                from(m), Reply.date(date(m)), m.path("body").path("content").asText(""), context);
    }

    /** Sends a reply as written (MIME, so it keeps its In-Reply-To and References); Outlook keeps it in Sent Items. */
    static void send(Config.Account a, MimeMessage message) throws Exception {
        ByteArrayOutputStream mime = new ByteArrayOutputStream();
        message.writeTo(mime);
        call(a, "POST", "/me/sendMail", Base64.getEncoder().encodeToString(mime.toByteArray()), "text/plain");
    }

    /** The bodies of your newest {@code n} sent emails, for {@link Reply#yourEmails}. */
    static List<String> sent(Config.Account a, int n) throws IOException, InterruptedException {
        JsonNode r = call(a, "GET", "/me/mailFolders/sentitems/messages" + query("$orderby", "sentDateTime desc", "$top", String.valueOf(n),
                "$select", "body"), null);
        List<String> out = new ArrayList<>();
        for (JsonNode m : r.path("value")) out.add(m.path("body").path("content").asText(""));
        return out;
    }

    /** Your mailbox's address, with a pass straight from signing in (before the sign-in is kept). Empty if Graph won't say. */
    static String me(String token) throws IOException, InterruptedException {
        HttpResponse<String> res = SignIn.HTTP.send(HttpRequest.newBuilder(URI.create(base + "/me" + query("$select", "mail,userPrincipalName")))
                .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return "";
        String mail = SignIn.JSON.readTree(res.body()).path("mail").asText("").strip();
        return SignIn.ACCOUNT.matcher(mail).matches() ? mail : "";
    }

    // ---- talking to Graph ----

    static JsonNode call(Config.Account a, String method, String path, String json) throws IOException, InterruptedException {
        return call(a, method, path, json, "application/json");
    }

    /**
     * One request to Graph as the account, with the body of emails as text. Waits and tries again when Graph says it's
     * busy; a pass Graph turns down is fetched again once.
     */
    static JsonNode call(Config.Account a, String method, String path, String body, String type) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + SignIn.token(a)).header("Accept", "application/json")
                    .header("Prefer", "outlook.body-content-type=\"text\"")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) req.header("Content-Type", type);
            HttpResponse<String> res = SignIn.HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            if ((status == 429 || status == 503) && attempt < 3) {
                long wait = res.headers().firstValueAsLong("Retry-After").orElse(5);
                TimeUnit.SECONDS.sleep(Math.max(1, Math.min(30, wait)));
                continue;
            }
            if (status == 401 && attempt == 1) {
                SignIn.TOKENS.remove(a.email());
                continue;
            }
            if (status == 401) throw new IOException("Microsoft wouldn't let Mail in to " + a.email() + ". Remove it in Mail's settings and add it again.");
            if (status >= 400) {
                String said;
                try {
                    said = SignIn.JSON.readTree(res.body()).path("error").path("message").asText("");
                } catch (Exception e) {
                    said = "";
                }
                throw new IOException("Outlook answered HTTP " + status + (said.isBlank() ? "" : ": " + SignIn.clip(said, 300)));
            }
            return res.body().isBlank() ? SignIn.JSON.createObjectNode() : SignIn.JSON.readTree(res.body());
        }
    }

    /** A query string: keys as they are ($filter…), values encoded, spaces as %20 (OData doesn't read + as one). */
    static String query(String... kv) {
        StringJoiner j = new StringJoiner("&", "?", "");
        for (int i = 0; i < kv.length; i += 2) j.add(kv[i] + "=" + enc(kv[i + 1]));
        return j.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A string in a $filter: quoted, its quotes doubled, so nothing in it can change the filter. */
    static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ---- reading Graph's emails ----

    /** The internet headers Graph has for an email, by lower-case name (Outlook leaves them out of mail sent inside it). */
    private static Map<String, String> headers(JsonNode m) {
        Map<String, String> h = new HashMap<>();
        for (JsonNode x : m.path("internetMessageHeaders")) h.putIfAbsent(x.path("name").asText("").toLowerCase(Locale.ROOT), x.path("value").asText(""));
        return h;
    }

    /** "Name <address>", as the IMAP path reads a header. */
    private static String address(JsonNode recipient) {
        JsonNode e = recipient.path("emailAddress");
        String name = e.path("name").asText(""), addr = e.path("address").asText("");
        return name.isBlank() || name.equalsIgnoreCase(addr) ? addr : name + " <" + addr + ">";
    }

    private static String addresses(JsonNode list) {
        StringJoiner j = new StringJoiner(", ");
        for (JsonNode r : list) j.add(address(r));
        return j.toString();
    }

    private static InternetAddress internet(JsonNode recipient) {
        JsonNode e = recipient.path("emailAddress");
        try {
            InternetAddress ia = new InternetAddress(e.path("address").asText(""), e.path("name").asText(null), "UTF-8");
            ia.validate();
            return ia;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String from(JsonNode m) {
        InternetAddress ia = internet(m.path("from"));
        return ia == null ? "someone" : ia.toUnicodeString();
    }

    private static Date date(JsonNode m) {
        Instant i = instant(m.path("sentDateTime"));
        return i == null ? null : Date.from(i);
    }

    private static Instant instant(JsonNode n) {
        try {
            return n.isTextual() ? Instant.parse(n.asText()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Each of {@code addresses} sent it, or is among its recipients. */
    private static boolean involvesAll(JsonNode m, List<String> addresses) {
        List<String> on = new ArrayList<>();
        for (JsonNode r : iterable(m.path("toRecipients"), m.path("ccRecipients"))) on.add(r.path("emailAddress").path("address").asText("").toLowerCase(Locale.ROOT));
        on.add(m.path("from").path("emailAddress").path("address").asText("").toLowerCase(Locale.ROOT));
        return addresses.stream().allMatch(x -> on.contains(x.toLowerCase(Locale.ROOT)));
    }

    private static List<JsonNode> iterable(JsonNode... lists) {
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode l : lists) l.forEach(all::add);
        return all;
    }
}
