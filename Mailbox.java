import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import java.util.Properties;

/**
 * Connecting to an account's mail: IMAP to read and mark, SMTP to send. Gmail with an app password signs in with it;
 * an account you signed in to Google or Microsoft for signs in with a pass from that sign-in (XOAUTH2), as Thunderbird
 * and Evolution do.
 */
public class Mailbox {

    /** Put over every connection's properties, last: a test trusts its own server here. */
    static final Properties BASE = new Properties();

    static Store store(Config.Account a) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.connectiontimeout", "30000");
        props.put("mail.imaps.timeout", "120000");
        if (a.signedIn()) props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        props.putAll(BASE);
        Store store = Session.getInstance(props).getStore("imaps");
        store.connect(a.imapHost(), a.imapPort(), a.login(), secret(a));
        return store;
    }

    /** A session to write a message in and send it with {@link #transport}. */
    static Session smtp(Config.Account a) {
        Properties props = new Properties();
        props.put("mail.smtp.host", a.smtpHost());
        props.put("mail.smtp.port", String.valueOf(a.smtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "120000");
        if (a.signedIn()) props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        props.putAll(BASE);
        return Session.getInstance(props);
    }

    static Transport transport(Session session, Config.Account a) throws Exception {
        Transport t = session.getTransport("smtp");
        t.connect(a.smtpHost(), a.smtpPort(), a.login(), secret(a));
        return t;
    }

    private static String secret(Config.Account a) throws Exception {
        return a.signedIn() ? SignIn.token(a) : a.password();
    }
}
