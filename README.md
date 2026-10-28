<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# mail-triage

A House Elf that sorts your unread mail with [Claude Code](https://claude.com/claude-code), for its **Mail** tab on [Elf Desk](https://github.com/House-elves/elf-desk). It reads Gmail, Google Workspace and Outlook (Office 365), as many accounts as you like.

Each run connects to each account over IMAP, fetches the unread emails (one per thread), and asks Claude whether each needs you. Noise is marked read, and at Gmail labelled with your noise label (Outlook has no labels over IMAP, so there it's only marked read). The rest stays unread and is listed on the Mail tab with the reason: one list for all your accounts, each email saying which account it came to. An account it couldn't read says why, at the top. **Done** on an email marks its thread read in its account.

**Reply** on an email asks Claude for a suggested reply, from the email and the part of its thread everyone you'd reply to already saw. You edit it, tick **Reply all** if it should also go to the email's other recipients, and **Send** sends it from the account the email came to (SMTP, signed in the same way) in the same thread, quoting the email, once you confirm it in the desk's bar. Who it goes to always comes from the email itself. Nothing else is ever sent.

Suggestions follow **How you write**, a short note at the top of Mail's Settings: write it yourself, or **Learn from my sent emails** has Claude write it from your recent sent emails (your own words only, without what they quote). It's kept in `~/.config/mail-triage/style/`.

**Find out**: a suggestion leaves what it can't know as [questions in brackets]. When one of them is about code, Find out has an agent look it up in a repo's code and docs and fill it in, showing what it found and where (file and line); Undo puts the suggestion back. The repo is filled in only when it's one of yours (from the GitHub elf's settings); a repo the thread points at is only shown, for you to type in. **Find out in my repos** saves typing one: Claude, with no tools, picks the one of your repos likeliest to answer the brackets, and can only answer one of yours. The agent is the runner's, in its sandbox, after the safety check of the thread, and can only read the repo's files (Read, Grep, Glob, refused anywhere else): no shell, no web, no MCP, so nothing it reads can leave except in its answer, which you read. It needs runner 0.5.7 or later.

Claude reads other people's words here, so every Claude call (triage and replies) runs with no tools (built in or MCP), none of your Claude settings, and in an empty folder.

Threads another elf already owns skip Claude and are marked read directly: GitHub notifications for Dependabot's `org.mvnpm` bumps on `quarkusio/quarkus`, which [mvnpm-dependabot](https://github.com/House-elves/mvnpm-dependabot) checks and comments on.

## Your accounts

They're listed at the top of Mail's Settings.

- **Gmail with an app password:** set **Gmail account** and **Gmail app password** in its settings ([make one](https://myaccount.google.com/apppasswords)).
- **Sign in to Google:** for Gmail without an app password, and Google Workspace accounts whose admin turned app passwords off. It opens Google's own sign-in page. Mail listens on this machine (127.0.0.1) for up to 5 minutes for your browser to come back from it.
- **Sign in to Microsoft:** for Outlook and Office 365, which no longer take a password over IMAP. It shows a code to type at [microsoft.com/devicelogin](https://microsoft.com/devicelogin).

Signing in works the way Thunderbird and Evolution do: your password goes only to Google or Microsoft. Mail is given a pass to read, mark and send your mail and nothing else in the account. It keeps that pass in `~/.config/mail-triage/signins.json`, readable only by you, until you **Remove** the account.

Google and Microsoft only let an app they know sign in, so each needs an app of yours:

- **Google:** in [Google Cloud](https://console.cloud.google.com):
  1. Make a project and turn on the Gmail API.
  2. Set up its OAuth consent screen (External), then **Publish app**, so it's *In production*. While it's *Testing*, Google ends its sign-ins after 7 days.
  3. Under *Credentials*, create an *OAuth client ID* of type *Desktop app*.
  4. Copy its client ID and secret into **Google client ID** and **Google client secret**.

  Signing in then warns that Google hasn't verified the app. It's your own app, so *Advanced* and *Go to …* is fine.
- **Microsoft:** in the [Microsoft Entra admin center](https://entra.microsoft.com):
  1. *App registrations*, *New registration*, any name, *Accounts in any organizational directory and personal Microsoft accounts*, no redirect address.
  2. Under *Authentication*, turn on *Allow public client flows*.
  3. Under *API permissions*, add Microsoft Graph's delegated `IMAP.AccessAsUser.All`, `SMTP.Send`, `offline_access`, `openid`, `email` and `profile`.
  4. Copy its *Application (client) ID* into **Microsoft app ID**.

  Calendar can use the same app: add `Calendars.Read` and `User.Read` to it too, so your admin has one app to allow.

A work account's admin decides which apps may sign in. If Google says access is blocked, or Microsoft says your organisation has to allow the app first, send IT your app's ID and ask them to allow it. It's the same kind of permission they gave Thunderbird or Evolution. Some organisations also turn IMAP or SMTP sign-in off for Outlook: then reading or sending says so, and only IT can turn it back on.

### An organisation's app, the way Evolution signs in

Your organisation may already have an app it allows for your mail, for example one it registered for Evolution, but not let it use IMAP or a sign-in code. Mail can sign in with that app the way Evolution does, and read and send through Microsoft Graph instead of IMAP and SMTP. Set these in Mail's settings, from that app's settings (in Evolution: the account's *Receiving Email*, *Advanced settings*):

- **Microsoft app ID:** its application (client) ID.
- **Microsoft organisation:** its tenant ID, if it's only for your organisation.
- **Microsoft return address:** its redirect URI, for example `https://login.microsoftonline.com/common/oauth2/nativeclient`.

**Sign in to Microsoft** then shows a link instead of a code. Sign in there, and you land on a blank page. Copy its whole address from the address bar and paste it into Mail straight away, because the one-time code in it only lasts a few minutes. Mail asks for less than Evolution does: only to read, mark and send your mail (`Mail.ReadWrite`, `Mail.Send`) and your name (`User.Read`). It only uses your own mailbox: the inbox, Sent Items, and sending your reply.

Using an app your organisation registered for another program is between you and your organisation. Check that it's allowed.

## Install

In Elf Desk, the **+** after the last tab: **Add** it, which adds the Mail tab. Then add your accounts in its Settings (see above). Once one is set up it sorts your inbox for the first time. After that it runs when you click **Refresh** (or the dashboard's **Refresh everything**) and on weekdays at 07:00, which you can change in its settings.

The desk reads [`elf.yaml`](elf.yaml): its settings, commands, tab and the actions its tab may ask for (Done, a suggested reply, and Send, which asks you first).

## Prerequisites

- Java 21+ and [JBang](https://www.jbang.dev/)
- The [Claude Code CLI](https://claude.com/claude-code) (`claude`), logged in
- A Gmail account with an app password, or an account you sign in to (see Your accounts)

## By hand

```bash
jbang MailTriage.java            # sort the inbox and save the run
jbang MailTriage.java --dry-run  # decide, but mark and label nothing
```

```bash
echo '{}' | jbang MailTriage.java --action=accounts            # the accounts it sorts
echo '{}' | jbang MailTriage.java --action=microsoft-sign-in   # a code to type at microsoft.com/devicelogin
echo '{}' | jbang MailTriage.java --action=microsoft-check     # once you have: keeps the sign-in
echo '{}' | jbang MailTriage.java --action=google-sign-in      # Google's page to open
echo '{}' | jbang MailTriage.java --action=google-check        # once you're back: keeps the sign-in
echo '{"account":"you@example.com"}' | jbang MailTriage.java --action=sign-out
```

Settings are in `~/.config/mail-triage/config` (only readable by you): `GMAIL_ADDRESS`, `GMAIL_APP_PASSWORD`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `MICROSOFT_APP_ID`, `MAX_EMAILS` (50) and `TRIAGE_LABEL` (`non-actionable`). More accounts can be added by hand as `GMAIL_ACCOUNT_1` and `GMAIL_PASSWORD_1`, and so on. Runs are saved in `~/.config/mail-triage/runs` for 90 days, and what you marked Done in `done/mail.json` for a month.
