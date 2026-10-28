# What's new

What changed for you in each version of the Mail elf, newest first. The desk shows the parts that are new when it offers you an
update.

## 1.7.0

- Outlook at an organisation that only allows its own app, the way Evolution signs in: set **Microsoft organisation** and
  **Microsoft return address**. Sign in to Microsoft then gives you a link, and you paste the address you land on.
  Mail then reads, marks and sends through Microsoft Graph, so it works where your organisation turned IMAP off.
- Sign-in errors from Microsoft say which setting to check.

## 1.6.0

- More than Gmail: sign in to Google for Google Workspace (and any Gmail, without an app password), and sign in to
  Microsoft for Outlook and Office 365. Your accounts are listed at the top of Mail's settings, with Remove.
- One list for all your accounts: each email says which account it came to, and replies go from that account.
- An account it couldn't read says why at the top of the tab, instead of looking empty.
- Signing in needs an app ID of yours from Google or Microsoft: the README says how to make one. Calendar can use the
  same Microsoft app.

## 1.5.0

- Find out in my repos: no need to type a repo first. Claude picks the one of your repos likeliest to answer the
  [brackets], and the agent looks there. Your repos are the GitHub elf's Repos I maintain.
- The repo box suggests your repos as you type.

## 1.4.0

- Find out: when a suggested reply leaves something open, an agent looks it up for you. Needs runner 0.5.7 or newer.

## 1.3.1

- Behind the scenes: the email thread goes to Claude in a clearer form.

## 1.3.0

- Suggested replies sound like you: set How you write in its settings.

## 1.2.0

- Reply from the tab: Claude suggests a reply, you edit it, and Send asks you first.

## 1.1.0

- See what Claude cost for each run, and this month's total.

## 1.0.0

- Sorts your unread Gmail with Claude: noise is marked read and labelled, and what needs you is listed in its tab.
