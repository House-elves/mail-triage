import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ~/.config/mail-triage/config: the accounts to triage and how. Elf Desk writes it from its settings. The accounts you
 * signed in to (Google or Microsoft) are kept apart, in {@link SignIn#FILE}.
 */
public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "mail-triage");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");

    String gmailAddress;
    String gmailAppPassword;
    int maxEmails;
    String triageLabel;

    Map<String, String> raw;

    /** How an account signs in: Gmail with an app password, or a sign-in to Google or to Microsoft. */
    enum Kind { GMAIL, GOOGLE, MICROSOFT }

    /**
     * An inbox to sort.
     *
     * @param login    who it signs in to the mail server as (Microsoft's sign-in name, else the address)
     * @param password the app password, for {@link Kind#GMAIL}; a sign-in's pass comes from {@link SignIn#token}
     */
    record Account(String email, String login, Kind kind, String password, String imapHost, int imapPort, String smtpHost,
            int smtpPort) {

        static Account gmail(String email, String password) {
            return new Account(email, email, Kind.GMAIL, password, "imap.gmail.com", 993, "smtp.gmail.com", 587);
        }

        static Account google(String email) {
            return new Account(email, email, Kind.GOOGLE, null, "imap.gmail.com", 993, "smtp.gmail.com", 587);
        }

        static Account microsoft(String email, String login) {
            return new Account(email, login, Kind.MICROSOFT, null, "outlook.office365.com", 993, "smtp.office365.com", 587);
        }

        /** Its mail is at Gmail, which has labels, threads and rfc822msgid: search of its own. */
        boolean gmail() {
            return imapHost.equals("imap.gmail.com");
        }

        boolean signedIn() {
            return kind != Kind.GMAIL;
        }

        /** What the tab calls its mail: "gmail" or "outlook". */
        String where() {
            return gmail() ? "gmail" : "outlook";
        }
    }

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Add mail-triage on Elf Desk's Elves page and fill in its settings.");
            System.exit(1);
        }
        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }
        Config c = new Config();
        c.raw = raw;
        c.gmailAddress = raw.getOrDefault("GMAIL_ADDRESS", "");
        c.gmailAppPassword = raw.getOrDefault("GMAIL_APP_PASSWORD", "");
        c.maxEmails = Integer.parseInt(raw.getOrDefault("MAX_EMAILS", "50"));
        c.triageLabel = raw.getOrDefault("TRIAGE_LABEL", "non-actionable");
        return c;
    }

    /**
     * The account from the desk's settings, any GMAIL_ACCOUNT_n / GMAIL_PASSWORD_n added by hand, then the accounts
     * you signed in to. An address is listed once, the first way it's set up.
     */
    List<Account> getAccounts() {
        List<Account> accounts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!gmailAddress.isEmpty() && !gmailAppPassword.isEmpty()) {
            accounts.add(Account.gmail(gmailAddress, gmailAppPassword));
            seen.add(gmailAddress.toLowerCase());
        }
        for (int i = 1; raw.containsKey("GMAIL_ACCOUNT_" + i); i++) {
            String addr = raw.get("GMAIL_ACCOUNT_" + i);
            if (seen.add(addr.toLowerCase())) accounts.add(Account.gmail(addr, raw.getOrDefault("GMAIL_PASSWORD_" + i, "")));
        }
        for (Account a : SignIn.accounts()) {
            if (seen.add(a.email().toLowerCase())) accounts.add(a);
        }
        return accounts;
    }
}
