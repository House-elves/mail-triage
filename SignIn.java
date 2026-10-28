import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Signing in to Google or Microsoft for an account whose mail won't take a password: a work account (Google Workspace
 * without app passwords, Office 365), or any Gmail you'd rather not make an app password for. It's how Thunderbird and
 * Evolution get in: you sign in in your browser, at Google's or Microsoft's own page, so your password never passes
 * through here, and Mail is given a pass to read, mark and send your mail and nothing else.
 *
 * <ul>
 * <li><b>Microsoft</b> signs in with a code: Mail shows it, you type it at microsoft.com/devicelogin. Mail then reads over
 * IMAP. An app that won't take a code (an organisation's app for Evolution, say) signs in as Evolution does instead:
 * you open Microsoft's page, sign in, and paste the address you land on (its return address, with a one-time code)
 * into Mail. Mail then reads through Microsoft Graph, which such an organisation allows where it doesn't allow IMAP.</li>
 * <li><b>Google</b> doesn't let mail be read that way, so Mail listens on this machine (127.0.0.1, for 5 minutes) for
 * your browser to come back from Google's page. The action that starts it can't wait that long, so it starts Mail
 * again in the background to listen.</li>
 * </ul>
 *
 * Either needs an app of yours that Google or Microsoft knows (its ID, which Add account asks for), and a work account's admin
 * may have to allow it. Sign-ins are kept in {@link #FILE}, readable only by you, never in a folder the tab reads.
 */
public class SignIn {

    static final Path FILE = Config.CONFIG_DIR.resolve("signins.json");
    static final Path LOCK = Config.CONFIG_DIR.resolve("signins.lock");
    static final ObjectMapper JSON = new ObjectMapper();

    /** Not final: a test answers in Microsoft's place. */
    static String msLogin = "https://login.microsoftonline.com/";
    /** Read and mark your mail (IMAP) and send it (SMTP), and keep signed in: nothing else in your account. */
    static final String MS_MAIL = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send";
    /** And, once, who you are: the sign-in's name and address. */
    static final String MS_SCOPES = "openid email profile " + MS_MAIL;
    /** Read and mark your mail and send it through Microsoft Graph, and keep signed in: what Evolution is allowed, less. */
    static final String MS_GRAPH = "offline_access https://graph.microsoft.com/Mail.ReadWrite https://graph.microsoft.com/Mail.Send https://graph.microsoft.com/User.Read";
    static final Duration MS_PASTE_WAIT = Duration.ofMinutes(30);

    static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    /** Not final: a test answers in Google's place. */
    static String googleToken = "https://oauth2.googleapis.com/token";
    /** Gmail over IMAP and SMTP needs all of mail.google.com: Google has no narrower pass for it. */
    static final String GOOGLE_SCOPES = "https://mail.google.com/ openid email";
    static final Duration GOOGLE_WAIT = Duration.ofMinutes(5);

    static final Pattern APP_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern GOOGLE_ID = Pattern.compile("[0-9]{4,30}-[0-9a-z]{10,64}\\.apps\\.googleusercontent\\.com");
    /** An organisation's tenant: its ID, a domain, or common / organizations. */
    static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,99}");
    /** A return address: HTTPS, or this machine. */
    static final Pattern REDIRECT = Pattern.compile("(https://[A-Za-z0-9.-]{1,253}|http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?)(/[\\x21-\\x7E&&[^?#]]{0,300})?");
    static final Pattern ACCOUNT = Pattern.compile("[\\w.+'-]{1,64}@[\\w.-]{1,250}");

    /** Passes already fetched this run, by address. A test puts its own here. */
    static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    // ---- the accounts you signed in to ----

    static List<Config.Account> accounts() {
        List<Config.Account> out = new ArrayList<>();
        read().path("accounts").properties().forEach(e -> {
            JsonNode a = e.getValue();
            if (a.path("kind").asText().equals("microsoft")) out.add(Config.Account.microsoft(e.getKey(), a.path("login").asText(e.getKey())));
            else if (a.path("kind").asText().equals("graph")) out.add(Config.Account.graph(e.getKey(), a.path("login").asText(e.getKey())));
            else if (a.path("kind").asText().equals("google")) out.add(Config.Account.google(e.getKey()));
            else if (a.path("kind").asText().equals("gmail")) out.add(Config.Account.gmail(e.getKey(), a.path("password").asText("")));
        });
        return out;
    }

    // ---- Add account: a Gmail with an app password, the apps signing in needs, and each account's colour ----

    static final Pattern APP_PASSWORD = Pattern.compile("[a-zA-Z]{4}( ?[a-zA-Z]{4}){3}");
    static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Adds a Gmail with an app password, once Gmail took it: answers {added}. */
    static ObjectNode addGmail(String address, String password) throws IOException {
        String email = address.strip().toLowerCase(Locale.ROOT), pass = password.replace(" ", "").strip();
        if (!ACCOUNT.matcher(email).matches()) throw new IllegalArgumentException("That isn't an email address.");
        if (!APP_PASSWORD.matcher(pass).matches()) {
            throw new IllegalArgumentException("An app password is 16 letters, from myaccount.google.com/apppasswords. Your own password won't do.");
        }
        try (var store = Mailbox.store(Config.Account.gmail(email, pass))) {
            // Signed in: Gmail took it.
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new IllegalArgumentException("Gmail didn't take that app password for " + email + ". Check both, or make a new app password.");
        } catch (Exception e) {
            throw new IOException("couldn't reach Gmail (" + clip(String.valueOf(e.getMessage()), 120) + ")");
        }
        update(s -> s.withObject("/accounts").putObject(email).put("kind", "gmail").put("password", pass).put("at", Instant.now().toString()));
        return JSON.createObjectNode().put("added", email);
    }

    /** Sets an account's colour, for its emails on the Mail tab. */
    static ObjectNode setColour(String account, String colour) throws IOException {
        String email = account.strip().toLowerCase(Locale.ROOT);
        if (!COLOUR.matcher(colour).matches()) throw new IllegalArgumentException("That isn't a colour.");
        boolean[] had = { false };
        update(s -> {
            if (s.path("accounts").has(email)) {
                had[0] = true;
                ((ObjectNode) s.get("accounts").get(email)).put("colour", colour.toLowerCase(Locale.ROOT));
            }
        });
        if (!had[0]) throw new IllegalArgumentException("That isn't one of Mail's accounts.");
        return JSON.createObjectNode();
    }

    static String colour(String email) {
        String c = read().path("accounts").path(email.toLowerCase(Locale.ROOT)).path("colour").asText("");
        return COLOUR.matcher(c).matches() ? c : "";
    }

    /** The Microsoft app and Google client signing in uses: {microsoft: {app, tenant, redirect}, google: {app, secret}}. */
    static ObjectNode apps(Map<String, String> raw) {
        ObjectNode out = JSON.createObjectNode();
        out.putObject("microsoft").put("app", raw.getOrDefault("MICROSOFT_APP_ID", "").strip())
                .put("tenant", raw.getOrDefault("MICROSOFT_TENANT", "").strip()).put("redirect", raw.getOrDefault("MICROSOFT_REDIRECT", "").strip());
        out.putObject("google").put("app", raw.getOrDefault("GOOGLE_CLIENT_ID", "").strip())
                .put("secret", !raw.getOrDefault("GOOGLE_CLIENT_SECRET", "").isBlank());
        return out;
    }

    /** Sets the Microsoft app, from Add account: its ID, and for an organisation's own app its organisation and return address. */
    static void setMicrosoftApp(String id, String tenant, String redirect) throws IOException {
        id = id.strip();
        tenant = tenant.strip();
        redirect = redirect.strip();
        if (!APP_ID.matcher(id).matches()) throw new IllegalArgumentException("The app ID is its Application (client) ID, like 1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f.");
        if (!tenant.isEmpty() && !TENANT.matcher(tenant).matches()) throw new IllegalArgumentException("The organisation is its Directory (tenant) ID or its domain, like contoso.com.");
        if (!redirect.isEmpty() && !REDIRECT.matcher(redirect).matches()) {
            throw new IllegalArgumentException("The return address is the app's redirect URI, like https://login.microsoftonline.com/common/oauth2/nativeclient.");
        }
        Config.set(Map.of("MICROSOFT_APP_ID", id, "MICROSOFT_TENANT", tenant, "MICROSOFT_REDIRECT", redirect));
    }

    /** Sets the Google client, from Add account. An empty secret keeps the one set, for the same client. */
    static void setGoogleApp(Map<String, String> raw, String id, String secret) throws IOException {
        id = id.strip();
        secret = secret.strip();
        if (!GOOGLE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("The client ID is the one Google Cloud shows for a Desktop app, ending .apps.googleusercontent.com.");
        }
        if (secret.isEmpty()) {
            if (!id.equals(raw.getOrDefault("GOOGLE_CLIENT_ID", "").strip()) || raw.getOrDefault("GOOGLE_CLIENT_SECRET", "").isBlank()) {
                throw new IllegalArgumentException("Give the client secret Google Cloud shows with that client ID too.");
            }
            Config.set(Map.of("GOOGLE_CLIENT_ID", id));
            return;
        }
        if (!secret.matches("[\\x21-\\x7E]{8,100}")) throw new IllegalArgumentException("That isn't a client secret: copy it from Google Cloud's Credentials page.");
        Config.set(Map.of("GOOGLE_CLIENT_ID", id, "GOOGLE_CLIENT_SECRET", secret));
    }

    /**
     * The organisation's own app each Evolution account on this machine signs in to Microsoft with (OverrideOauth2 in
     * its account file), so Add account can offer it. An empty return address there is Microsoft's nativeclient page.
     */
    static ArrayNode evolutionApps() {
        ArrayNode out = JSON.createArrayNode();
        Set<String> seen = new java.util.HashSet<>();
        Path home = Path.of(System.getProperty("user.home"));
        for (Path sources : List.of(home.resolve(".config/evolution/sources"), home.resolve(".var/app/org.gnome.Evolution/config/evolution/sources"))) {
            if (!Files.isDirectory(sources)) continue;
            try (var files = Files.list(sources)) {
                for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".source")).toList()) {
                    Map<String, String> keys = new HashMap<>();
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        if (eq > 0 && !line.startsWith("#")) keys.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                    }
                    if (!"true".equals(keys.get("OverrideOauth2"))) continue;
                    String app = keys.getOrDefault("Oauth2ClientId", ""), tenant = keys.getOrDefault("Oauth2Tenant", "");
                    String redirect = keys.getOrDefault("Oauth2RedirectUri", "");
                    if (redirect.isEmpty()) redirect = "https://login.microsoftonline.com/common/oauth2/nativeclient";
                    if (!APP_ID.matcher(app).matches() || !(tenant.isEmpty() || TENANT.matcher(tenant).matches()) || !REDIRECT.matcher(redirect).matches()) continue;
                    if (seen.add(app + " " + tenant + " " + redirect)) {
                        out.addObject().put("app", app).put("tenant", tenant).put("redirect", redirect).put("account", clip(keys.getOrDefault("DisplayName", ""), 100));
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** Forgets the sign-in for {@code email}. Its runs stay. */
    static boolean forget(String email) throws IOException {
        boolean[] had = { false };
        update(s -> {
            if (s.path("accounts").has(email)) {
                had[0] = true;
                s.withObject("/accounts").remove(email);
            }
        });
        return had[0];
    }

    /** A pass to the account's mail, from its kept sign-in. Keeps the new sign-in when Google or Microsoft gives one. */
    static String token(Config.Account a) throws IOException, InterruptedException {
        String cached = TOKENS.get(a.email());
        if (cached != null) return cached;
        boolean microsoft = a.kind() == Config.Kind.MICROSOFT || a.graph();
        String who = microsoft ? "Microsoft" : "Google";
        JsonNode kept = read().path("accounts").path(a.email());
        String refresh = kept.path("refreshToken").asText("");
        if (refresh.isEmpty()) throw new IOException("Mail isn't signed in to " + a.email() + ". Add it again in Mail's settings.");
        Map<String, String> raw = Config.load().raw;
        // At the organisation it signed in at, which may since have changed in the settings.
        JsonNode r = microsoft
                ? post(msLogin(kept.path("tenant").asText(tenant(raw))) + "token", Map.of("client_id", appId(raw), "grant_type", "refresh_token",
                        "refresh_token", refresh, "scope", a.graph() ? MS_GRAPH : MS_MAIL))
                : post(googleToken, Map.of("client_id", googleId(raw), "client_secret", googleSecret(raw), "grant_type", "refresh_token",
                        "refresh_token", refresh));
        if (!r.hasNonNull("access_token")) {
            throw new IOException(r.path("error").asText("").equals("invalid_grant")
                    ? "Mail's sign-in to " + a.email() + " ran out or was taken back. Remove it in Mail's settings and add it again."
                    : who + " wouldn't let Mail in to " + a.email() + ": " + error(r));
        }
        String next = r.path("refresh_token").asText("");
        if (!next.isEmpty() && !next.equals(refresh)) {
            update(s -> {
                if (s.path("accounts").has(a.email())) ((ObjectNode) s.get("accounts").get(a.email())).put("refreshToken", next);
            });
        }
        TOKENS.put(a.email(), r.get("access_token").asText());
        return r.get("access_token").asText();
    }

    // ---- Microsoft: a code to type ----

    static String appId(Map<String, String> raw) {
        String id = raw.getOrDefault("MICROSOFT_APP_ID", "").strip();
        if (!APP_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Signing in to Microsoft needs an app ID. Add account, Microsoft asks for it, and Mail's README says how to get one.");
        }
        return id;
    }

    /** The organisation Microsoft signs in at: Microsoft organisation in the settings, else any (common). */
    static String tenant(Map<String, String> raw) {
        String t = raw.getOrDefault("MICROSOFT_TENANT", "").strip();
        if (t.isEmpty()) return "common";
        if (!TENANT.matcher(t).matches()) {
            throw new IllegalArgumentException("The app's organisation should be its Directory (tenant) ID or its domain, like contoso.com. Change it with Add account, Microsoft, Change app.");
        }
        return t;
    }

    static String msLogin(String tenant) {
        return msLogin + tenant + "/oauth2/v2.0/";
    }

    /** Microsoft return address in the settings, for an app that won't take a code; null without one. */
    static String redirect(Map<String, String> raw) {
        String r = raw.getOrDefault("MICROSOFT_REDIRECT", "").strip();
        if (r.isEmpty()) return null;
        if (!REDIRECT.matcher(r).matches()) {
            throw new IllegalArgumentException("The app's return address should be its redirect URI, like https://login.microsoftonline.com/common/oauth2/nativeclient.");
        }
        return r;
    }

    /**
     * Starts signing in: answers {code, url, interval}, the code to type and where. With a return address, it answers
     * {url, paste: true} instead: Microsoft's page to sign in at, and you paste where you land into {@link #microsoftFinish}.
     */
    static ObjectNode microsoftStart(Config cfg) throws IOException, InterruptedException {
        String clientId = appId(cfg.raw), tenant = tenant(cfg.raw), redirect = redirect(cfg.raw);
        if (redirect != null) {
            String verifier = random(48), state = random(24);
            String until = Instant.now().plus(MS_PASTE_WAIT).toString();
            update(s -> s.putObject("microsoft").put("state", state).put("verifier", verifier).put("tenant", tenant)
                    .put("redirect", redirect).put("until", until));
            Map<String, String> q = new LinkedHashMap<>();
            q.put("client_id", clientId);
            q.put("response_type", "code");
            q.put("redirect_uri", redirect);
            q.put("response_mode", "query");
            q.put("scope", "openid email profile " + MS_GRAPH);
            q.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)));
            q.put("code_challenge_method", "S256");
            q.put("state", state);
            q.put("prompt", "select_account");
            return JSON.createObjectNode().put("url", msLogin(tenant) + "authorize?" + form(q)).put("paste", true);
        }
        JsonNode r = post(msLogin(tenant) + "devicecode", Map.of("client_id", clientId, "scope", MS_SCOPES));
        if (!r.hasNonNull("device_code") || !r.hasNonNull("user_code")) throw new IllegalArgumentException("Microsoft wouldn't start signing in: " + error(r));
        String deviceCode = r.get("device_code").asText();
        String until = Instant.now().plusSeconds(Math.min(1800, r.path("expires_in").asLong(900))).toString();
        update(s -> s.putObject("microsoft").put("deviceCode", deviceCode).put("tenant", tenant).put("until", until));
        // Only Microsoft's own sign-in page is shown as a link.
        String url = r.path("verification_uri").asText("");
        if (!url.matches("https://(www\\.)?microsoft\\.com/[\\w/]{1,40}|https://login\\.microsoftonline\\.com/[\\w/]{1,60}")) url = "https://microsoft.com/devicelogin";
        String code = r.get("user_code").asText();
        return JSON.createObjectNode().put("code", code.length() <= 20 ? code : code.substring(0, 20)).put("url", url)
                .put("interval", Math.max(3, Math.min(30, r.path("interval").asInt(5))));
    }

    /** Answers {"waiting": true} until you've typed the code and signed in; then keeps the sign-in and answers {added}. */
    static ObjectNode microsoftCheck(Config cfg) throws IOException, InterruptedException {
        JsonNode pending = read().path("microsoft");
        if (!pending.hasNonNull("deviceCode")) throw new IllegalArgumentException("Click Sign in to Microsoft first.");
        if (Instant.parse(pending.path("until").asText(Instant.EPOCH.toString())).isBefore(Instant.now())) {
            update(s -> s.remove("microsoft"));
            throw new IllegalArgumentException("The code ran out before you signed in. Click Sign in to Microsoft again.");
        }
        String tenant = pending.path("tenant").asText("common");
        JsonNode r = post(msLogin(tenant) + "token", Map.of("client_id", appId(cfg.raw), "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "device_code", pending.get("deviceCode").asText()));
        String err = r.path("error").asText("");
        if (err.equals("authorization_pending") || err.equals("slow_down")) return JSON.createObjectNode().put("waiting", true);
        update(s -> s.remove("microsoft"));
        if (!r.hasNonNull("refresh_token") || !r.hasNonNull("access_token")) {
            throw new IllegalArgumentException(err.equals("authorization_declined") ? "You said no in the browser, so Mail isn't signed in."
                    : err.equals("expired_token") ? "The code ran out before you signed in. Click Sign in to Microsoft again." : "Couldn't sign in: " + error(r));
        }
        // Who signed in, from Microsoft's own answer: its sign-in name, which Outlook's mail servers take, and address.
        JsonNode id = claims(r.path("id_token").asText(""));
        String login = id.path("preferred_username").asText("").strip().toLowerCase(Locale.ROOT);
        String email = id.path("email").asText(login).strip().toLowerCase(Locale.ROOT);
        if (!ACCOUNT.matcher(login).matches() || !ACCOUNT.matcher(email).matches()) {
            throw new IllegalArgumentException("Signed in, but Microsoft didn't say which account it is. Try again.");
        }
        String refresh = r.get("refresh_token").asText();
        update(s -> s.withObject("/accounts").putObject(email).put("kind", "microsoft").put("login", login).put("tenant", tenant)
                .put("refreshToken", refresh).put("at", Instant.now().toString()));
        return JSON.createObjectNode().put("added", email);
    }

    /**
     * Finishes a sign-in with a return address: {@code address} is where your browser landed after Microsoft's page, its
     * one-time code in it. That code is only good with the key {@link #microsoftStart} kept, for the sign-in it began.
     * Keeps the sign-in and answers {added}.
     */
    static ObjectNode microsoftFinish(Config cfg, String address) throws IOException, InterruptedException {
        JsonNode pending = read().path("microsoft");
        if (!pending.hasNonNull("state")) throw new IllegalArgumentException("Click Sign in to Microsoft first.");
        if (Instant.parse(pending.path("until").asText(Instant.EPOCH.toString())).isBefore(Instant.now())) {
            update(s -> s.remove("microsoft"));
            throw new IllegalArgumentException("That sign-in was started more than 30 minutes ago. Click Sign in to Microsoft again.");
        }
        String redirect = pending.path("redirect").asText(), tenant = pending.path("tenant").asText("common");
        String pasted = address == null ? "" : address.strip();
        int q = pasted.indexOf('?');
        if (q < 0 || !pasted.substring(0, q).equals(redirect)) {
            throw new IllegalArgumentException("That isn't the page you land on after signing in. Copy the whole address of the blank page, starting " + redirect + "?code=");
        }
        Map<String, String> got = query(pasted.substring(q + 1).replaceAll("#.*$", ""));
        if (got.containsKey("error")) {
            update(s -> s.remove("microsoft"));
            throw new IllegalArgumentException(got.get("error").equals("access_denied") ? "You said no to Microsoft, so Mail isn't signed in."
                    : "Microsoft said no: " + error(JSON.createObjectNode().put("error", got.get("error")).put("error_description", got.getOrDefault("error_description", ""))));
        }
        if (!pending.path("state").asText().equals(got.get("state"))) {
            throw new IllegalArgumentException("That address is from another sign-in. Use the link Mail shows now, then paste where it lands.");
        }
        update(s -> s.remove("microsoft"));
        JsonNode r = post(msLogin(tenant) + "token", Map.of("client_id", appId(cfg.raw), "grant_type", "authorization_code",
                "code", got.getOrDefault("code", ""), "redirect_uri", redirect, "code_verifier", pending.path("verifier").asText(), "scope", MS_GRAPH));
        if (!r.hasNonNull("refresh_token") || !r.hasNonNull("access_token")) throw new IllegalArgumentException("Couldn't sign in: " + error(r));
        if (!(" " + r.path("scope").asText("") + " ").toLowerCase(Locale.ROOT).contains("mail.readwrite")) {
            throw new IllegalArgumentException("Signed in, but Microsoft didn't allow Mail to read your mail. Your organisation may have to allow it.");
        }
        String login = claims(r.path("id_token").asText("")).path("preferred_username").asText("").strip().toLowerCase(Locale.ROOT);
        // Your mailbox's own address, which may not be the name you sign in with.
        String email = Graph.me(r.get("access_token").asText()).toLowerCase(Locale.ROOT);
        if (email.isEmpty()) email = login;
        if (!ACCOUNT.matcher(login).matches() || !ACCOUNT.matcher(email).matches()) {
            throw new IllegalArgumentException("Signed in, but Microsoft didn't say which account it is. Try again.");
        }
        String refresh = r.get("refresh_token").asText(), mailbox = email;
        update(s -> s.withObject("/accounts").putObject(mailbox).put("kind", "graph").put("login", login).put("tenant", tenant)
                .put("refreshToken", refresh).put("at", Instant.now().toString()));
        TOKENS.remove(mailbox);
        return JSON.createObjectNode().put("added", mailbox);
    }

    // ---- Google: back to this machine from Google's page ----

    static String googleId(Map<String, String> raw) {
        String id = raw.getOrDefault("GOOGLE_CLIENT_ID", "").strip();
        if (!GOOGLE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Signing in to Google needs a client ID, like 1234-abc….apps.googleusercontent.com. Add account, Google asks for it, and Mail's README says how to make one.");
        }
        return id;
    }

    static String googleSecret(Map<String, String> raw) {
        String secret = raw.getOrDefault("GOOGLE_CLIENT_SECRET", "").strip();
        if (secret.isEmpty()) throw new IllegalArgumentException("Signing in to Google needs the client secret that goes with its client ID. Add account, Google, Change client.");
        return secret;
    }

    /**
     * Starts signing in to Google: Mail listens on 127.0.0.1 in the background, and this answers {url}, Google's page
     * to open. Google sends your browser back to Mail there once you've signed in.
     */
    static ObjectNode googleStart(Config cfg) throws IOException, InterruptedException {
        String clientId = googleId(cfg.raw);
        googleSecret(cfg.raw);
        String verifier = random(48), state = random(24);
        String until = Instant.now().plus(GOOGLE_WAIT).toString();
        update(s -> s.putObject("google").put("state", state).put("verifier", verifier).put("until", until));
        // Mail again, as the runner started it, but to listen.
        ProcessHandle.Info me = ProcessHandle.current().info();
        String[] args = me.arguments().orElseThrow(() -> new IOException("Couldn't start listening for Google's answer"));
        List<String> cmd = new ArrayList<>(List.of(me.command().orElseThrow(() -> new IOException("Couldn't start listening for Google's answer"))));
        for (String a : args) cmd.add(a.equals("--action=google-sign-in") ? "--google-listen" : a);
        if (!cmd.contains("--google-listen")) throw new IOException("Couldn't start listening for Google's answer");
        new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Config.CONFIG_DIR.resolve("google-sign-in.log").toFile()))
                .start();
        int port = 0;
        for (int i = 0; i < 100 && port == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(200);
            JsonNode p = read().path("google");
            if (!p.path("state").asText().equals(state)) throw new IOException("Another sign-in to Google started meanwhile");
            port = p.path("port").asInt(0);
        }
        if (port == 0) throw new IOException("Mail couldn't listen for Google's answer: see ~/.config/mail-triage/google-sign-in.log");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", clientId);
        q.put("redirect_uri", "http://127.0.0.1:" + port);
        q.put("response_type", "code");
        q.put("scope", GOOGLE_SCOPES);
        q.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)));
        q.put("code_challenge_method", "S256");
        q.put("state", state);
        q.put("access_type", "offline");
        q.put("prompt", "select_account consent"); // a pass that lasts, every time, for the account you pick
        return JSON.createObjectNode().put("url", GOOGLE_AUTH + "?" + form(q)).put("interval", 3);
    }

    /** Answers {"waiting": true} until you're back from Google's page; then {added}, or throws with what went wrong. */
    static ObjectNode googleCheck() throws IOException {
        JsonNode p = read().path("google");
        if (!p.hasNonNull("state")) throw new IllegalArgumentException("Click Sign in to Google first.");
        JsonNode result = p.path("result");
        if (result.hasNonNull("added")) {
            update(s -> s.remove("google"));
            return JSON.createObjectNode().put("added", result.get("added").asText());
        }
        if (result.hasNonNull("error")) {
            update(s -> s.remove("google"));
            throw new IllegalArgumentException(result.get("error").asText());
        }
        if (Instant.parse(p.path("until").asText(Instant.EPOCH.toString())).isBefore(Instant.now())) {
            update(s -> s.remove("google"));
            throw new IllegalArgumentException("Mail stopped waiting for Google after 5 minutes. If Google said access is blocked, "
                    + "your organisation hasn't allowed your Google client: ask IT to allow its client ID. Otherwise click Sign in to Google again.");
        }
        return JSON.createObjectNode().put("waiting", true);
    }

    /**
     * --google-listen: waits on 127.0.0.1 for your browser to come back from Google, for the sign-in {@link #googleStart}
     * began, at most 5 minutes. Takes one answer with that sign-in's state, then stops.
     */
    static int googleListen(Config cfg) throws Exception {
        JsonNode p = read().path("google");
        String state = p.path("state").asText(""), verifier = p.path("verifier").asText("");
        if (state.isEmpty()) return 1;
        Instant until = Instant.parse(p.path("until").asText(Instant.EPOCH.toString()));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        int port = server.getAddress().getPort();
        boolean[] ours = { false };
        update(s -> {
            if (s.path("google").path("state").asText().equals(state)) {
                ((ObjectNode) s.get("google")).put("port", port);
                ours[0] = true;
            }
        });
        if (!ours[0]) return 1;
        CountDownLatch over = new CountDownLatch(1);
        server.createContext("/", ex -> {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (!state.equals(q.get("state"))) {
                page(ex, 400, "This isn't the sign-in Mail is waiting for. Go back to Elf Desk and click Sign in to Google again.");
                return;
            }
            String said;
            ObjectNode result = JSON.createObjectNode();
            try {
                if (q.containsKey("error")) {
                    throw new IllegalArgumentException(q.get("error").equals("access_denied")
                            ? "You said no to Google, so Mail isn't signed in." : "Google said no: " + clip(q.get("error"), 100));
                }
                String email = googleExchange(cfg, q.getOrDefault("code", ""), verifier, port);
                result.put("added", email);
                said = "Mail is signed in to " + email + ". You can close this tab and go back to Elf Desk.";
            } catch (Exception e) {
                result.put("error", e.getMessage());
                said = e.getMessage();
            }
            update(s -> {
                if (s.path("google").path("state").asText().equals(state)) ((ObjectNode) s.get("google")).set("result", result);
            });
            page(ex, 200, said);
            over.countDown();
        });
        server.start();
        try {
            over.await(Math.max(0, Duration.between(Instant.now(), until).toMillis()), TimeUnit.MILLISECONDS);
        } finally {
            server.stop(1);
        }
        return 0;
    }

    /** The code Google sent back, for a pass that lasts: keeps the sign-in and answers its address. */
    static String googleExchange(Config cfg, String code, String verifier, int port) throws IOException, InterruptedException {
        if (code.isBlank()) throw new IllegalArgumentException("Google didn't send a sign-in back. Click Sign in to Google again.");
        JsonNode r = post(googleToken, Map.of("client_id", googleId(cfg.raw), "client_secret", googleSecret(cfg.raw), "code", code,
                "code_verifier", verifier, "grant_type", "authorization_code", "redirect_uri", "http://127.0.0.1:" + port));
        if (!r.hasNonNull("access_token")) throw new IllegalArgumentException("Google wouldn't sign Mail in: " + error(r));
        if (!r.hasNonNull("refresh_token")) throw new IllegalArgumentException("Google didn't give Mail a sign-in that lasts. Click Sign in to Google again.");
        if (!(" " + r.path("scope").asText("") + " ").contains(" https://mail.google.com/ ")) {
            throw new IllegalArgumentException("Google signed in, but without access to your mail: tick it on Google's page when you sign in again.");
        }
        String email = claims(r.path("id_token").asText("")).path("email").asText("").strip().toLowerCase(Locale.ROOT);
        if (!ACCOUNT.matcher(email).matches()) throw new IllegalArgumentException("Signed in, but Google didn't say which account it is. Try again.");
        String refresh = r.get("refresh_token").asText();
        update(s -> s.withObject("/accounts").putObject(email).put("kind", "google").put("refreshToken", refresh)
                .put("at", Instant.now().toString()));
        return email;
    }

    private static void page(HttpExchange ex, int status, String said) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><title>Mail</title><body style=\"font:16px system-ui;margin:3rem\"><p>"
                + said.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    // ---- what's kept ----

    static ObjectNode read() {
        try {
            return Files.exists(FILE) ? (ObjectNode) JSON.readTree(FILE.toFile()) : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    /** Changes what's kept, one change at a time (the background listener and the tab's checks both write it). */
    static void update(Consumer<ObjectNode> change) throws IOException {
        Files.createDirectories(Config.CONFIG_DIR);
        try (FileChannel lock = FileChannel.open(LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var held = lock.lock()) {
            ObjectNode s = read();
            change.accept(s);
            Path tmp = FILE.resolveSibling("signins.json.new");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, JSON.writeValueAsString(s));
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ---- helpers ----

    static JsonNode post(String url, Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form))).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        try {
            return JSON.readTree(res.body());
        } catch (Exception e) {
            throw new IOException("Signing in answered HTTP " + res.statusCode() + ", not what was expected");
        }
    }

    /** What a sign-in error says, in a line, with what to do when it's the organisation that said no. */
    static String error(JsonNode r) {
        String d = r.path("error_description").asText(r.path("error").asText("no answer"));
        if (d.contains("AADSTS65001") || d.contains("AADSTS90094") || d.contains("AADSTS90095") || d.contains("admin_policy_enforced")) {
            return "your organisation has to allow Mail's app first. Send your admin its app ID and ask them to allow it.";
        }
        if (d.contains("AADSTS7000218")) {
            return "that app won't take a sign-in code. Turn on Allow public client flows in its Authentication settings, or give its redirect URI as the return address: Add account, Microsoft, Change app.";
        }
        if (d.contains("AADSTS50011")) return "the app doesn't know that return address. Check the return address: Add account, Microsoft, Change app.";
        if (d.contains("AADSTS700016") || d.contains("AADSTS700038")) return "Microsoft doesn't know that app ID. Check the app ID: Add account, Microsoft, Change app.";
        if (d.contains("The OAuth client was not found") || d.contains("invalid_client")) return "Google doesn't know that client. Check its client ID and secret: Add account, Google, Change client.";
        d = d.lines().findFirst().orElse(d);
        int trace = d.indexOf(" Trace ID:"); // Microsoft's own references, for its support, not for you
        return clip(trace > 0 ? d.substring(0, trace) : d, 300);
    }

    /** The claims of a sign-in's id_token. It came straight from Google or Microsoft over HTTPS, so it's only read. */
    static JsonNode claims(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return JSON.createObjectNode();
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    static String form(Map<String, String> values) {
        StringJoiner j = new StringJoiner("&");
        values.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) q.putIfAbsent(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** The accounts list for the Settings pop-up: [{email, kind, where, colour}], app passwords first. */
    static ArrayNode list(Config cfg) {
        ArrayNode out = JSON.createArrayNode();
        for (Config.Account a : cfg.getAccounts()) {
            out.addObject().put("email", a.email()).put("kind", a.kind().name().toLowerCase(Locale.ROOT)).put("where", a.where())
                    .put("colour", colour(a.email()));
        }
        return out;
    }
}
