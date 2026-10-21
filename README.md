<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# mail-triage

A House Elf that sorts your unread Gmail with [Claude Code](https://claude.com/claude-code), for its **Mail** tab on [Elf Desk](https://github.com/House-elves/elf-desk).

Each run connects to your Gmail over IMAP, fetches the unread emails (one per thread), and asks Claude whether each needs you. Noise is marked read and labelled with your noise label; the rest stays unread and is listed on the Mail tab with the reason. **Done** on an email marks its thread read in Gmail. It sends nothing.

Threads another elf already owns skip Claude and are marked read directly: GitHub notifications for Dependabot's `org.mvnpm` bumps on `quarkusio/quarkus`, which [mvnpm-dependabot](https://github.com/House-elves/mvnpm-dependabot) checks and comments on.

## Install

From Elf Desk's **Elves** page: **Install** adds the Mail tab, which asks for your Gmail account and an [app password](https://myaccount.google.com/apppasswords). Once they're saved it sorts your inbox for the first time. After that it runs when you click **Refresh** (or the dashboard's **Refresh everything**) and on weekdays at 07:00, which you can change in its settings.

The desk reads [`elf.yaml`](elf.yaml): its settings, commands, tab and the one action its tab may ask for (Done).

## Prerequisites

- Java 21+ and [JBang](https://www.jbang.dev/)
- The [Claude Code CLI](https://claude.com/claude-code) (`claude`), logged in
- A Gmail account with an app password

## By hand

```bash
jbang MailTriage.java            # sort the inbox and save the run
jbang MailTriage.java --dry-run  # decide, but mark and label nothing
```

Settings are in `~/.config/mail-triage/config` (only readable by you): `GMAIL_ADDRESS`, `GMAIL_APP_PASSWORD`, `MAX_EMAILS` (50) and `TRIAGE_LABEL` (`non-actionable`). More accounts can be added by hand as `GMAIL_ACCOUNT_1` and `GMAIL_PASSWORD_1`, and so on. Runs are saved in `~/.config/mail-triage/runs` for 90 days, and what you marked Done in `done/mail.json` for a month.
