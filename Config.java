import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ~/.config/mail-triage/config: the Gmail accounts to triage and how. Elf Desk writes it from its settings. */
public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "mail-triage");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");

    String gmailAddress;
    String gmailAppPassword;
    int maxEmails;
    String triageLabel;

    private Map<String, String> raw;

    record GmailAccount(String email, String password) {}

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

    /** The account from the desk's settings, then any GMAIL_ACCOUNT_n / GMAIL_PASSWORD_n added by hand. */
    List<GmailAccount> getGmailAccounts() {
        List<GmailAccount> accounts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!gmailAddress.isEmpty() && !gmailAppPassword.isEmpty()) {
            accounts.add(new GmailAccount(gmailAddress, gmailAppPassword));
            seen.add(gmailAddress.toLowerCase());
        }
        for (int i = 1; raw.containsKey("GMAIL_ACCOUNT_" + i); i++) {
            String addr = raw.get("GMAIL_ACCOUNT_" + i);
            if (seen.add(addr.toLowerCase())) accounts.add(new GmailAccount(addr, raw.getOrDefault("GMAIL_PASSWORD_" + i, "")));
        }
        return accounts;
    }
}
