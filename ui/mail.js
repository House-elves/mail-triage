// mail-triage's tab on Elf Desk, and its dashboard card: across your accounts (Gmail, Google Workspace, Outlook), what
// needs you and what the triage marked read. Done marks the thread read in its account, through the desk.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, took, count, isToday, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';
import { runs, run, done, aiThisMonth, usd } from './runs.js';
import './reply.js';


/** The email in Gmail's web page. Outlook's has no link by Message-ID, so its emails have none. */
function gmail(a, d) {
    if (!d.messageId || (a.where ?? 'gmail') !== 'gmail') return null;
    const account = a.email;
    const id = d.messageId.replace(/^<|>$/g, '');
    return `https://mail.google.com/mail/?authuser=${encodeURIComponent(account)}#search/rfc822msgid:${encodeURIComponent(id)}`;
}

/** Which email, in which account: the same email can reach two of yours. */
const key = (account, d) => `${account}\n${d.messageId}`;

/** The run's accounts, each decision with when you marked it Done (doneAt). */
function withDone(data, marks) {
    return (data?.triage?.accounts ?? []).map(a => ({
        ...a,
        decisions: (a.decisions ?? []).map(d => ({ ...d, doneAt: marks[a.email]?.[d.messageId] ?? null })),
    }));
}

class MailTab extends LitElement {

    static properties = {
        _runs: { state: true },
        _run: { state: true },
        _aiMonth: { state: true },   // what Claude cost over this month's runs, or null
        _replying: { state: true },  // the email whose reply is open (key())
        _accounts: { state: true },
        _picked: { state: true },
        _doing: { state: true },
        _hiding: { state: true },
        _all: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        section { display: grid; gap: 14px; }
        .account { font-weight: 600; font-size: 15px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li { padding: 11px 18px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 3px 14px; align-items: center; }
        li > .text { display: grid; gap: 3px; min-width: 0; }
        li + li { border-top: 1px solid var(--line); }
        li .acts { display: flex; gap: 8px; }
        li elf-reply.reply { grid-column: 1 / -1; }
        .subject { font-weight: 500; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.subject:hover { text-decoration: underline; }
        .from, .why { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        .to { white-space: nowrap; }
        details > summary { cursor: pointer; padding: 11px 18px; font-size: 14px; color: var(--muted); }
        details[open] > summary { border-bottom: 1px solid var(--line); }
        .empty { color: var(--muted); padding: 14px 18px; margin: 0; font-size: 14px; }
        .skeleton.big { height: 40vh; }
        .all { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._picked = null;
        this._loading = true;
        this._hiding = new Set();
        this._queue = Promise.resolve();
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._loading = true;
        this._error = null;
        try {
            this._runs = await runs();
            this._run = await run(this._runs, this._picked);
            await this._marks();
            this._aiMonth = await aiThisMonth().catch(() => null);
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    /**
     * ", AI $0.03 (this month $1.20)": what Claude cost for this run and this month. Claude Code's figure at API
     * prices: on a subscription, what it would have cost.
     */
    _aiCost(r) {
        if (typeof r.data?.aiCost !== 'number') return '';
        return html`, <span title="What Claude cost: Claude Code's figure at API prices (on a subscription, not what you're billed)">AI ${usd(r.data.aiCost)}${this._aiMonth != null ? ` (this month ${usd(this._aiMonth)})` : ''}</span>`;
    }

    async _marks() {
        this._accounts = withDone(this._run?.data, await done());
    }

    /** Done, or on its way there: clicked, and its account hasn't answered yet. */
    _isDone(account, d) {
        return !!d.doneAt || this._hiding.has(key(account, d));
    }

    /**
     * Done: the email moves to the done list straight away, so the next one can be read, and its account marks the
     * thread read in the background. The clicks go one at a time. One its account refuses comes back, with the error.
     */
    _done(account, d) {
        const id = d.messageId, k = key(account, d);
        this._hiding = new Set(this._hiding).add(k);
        this._error = null;
        this._queue = this._queue.then(async () => {
            try {
                await elf.act('done', { account, messageId: id });
                await this._marks();
            } catch (e) {
                this._error = `${d.subject || 'That email'}: ${e.message}`;
            } finally {
                const hiding = new Set(this._hiding);
                hiding.delete(k);
                this._hiding = hiding;
            }
        });
    }

    /** What still needs you in the newest run, across accounts: what Done all marks. */
    _open() {
        return (this._accounts ?? []).flatMap(a => (a.decisions ?? [])
            .filter(d => d.decision !== 'MARK_READ' && !this._isDone(a.email, d) && d.messageId).map(d => ({ account: a.email, d })));
    }

    /** Done all: each email in turn, as if you clicked its Done. One that fails is left for you; the rest go on. */
    async _doneAll() {
        const todo = this._open();
        const failed = [];
        this._error = null;
        for (let i = 0; i < todo.length; i++) {
            const { account, d } = todo[i];
            this._all = { at: i + 1, of: todo.length };
            this._doing = key(account, d);
            try {
                await elf.act('done', { account, messageId: d.messageId });
            } catch (e) {
                failed.push(`${d.subject || 'An email'}: ${e.message}`);
            }
        }
        await this._marks();
        this._doing = null;
        this._all = null;
        if (failed.length) this._error = 'Not marked read: ' + failed.join('; ');
    }

    _allBar() {
        const n = this._open().length;
        if (this._picked || (!n && !this._all)) return '';
        if (this._all?.of) return html`<div class="all" role="status">${spinner} Marking ${this._all.at} of ${this._all.of} read…</div>`;
        if (this._all === 'confirm') return html`<div class="all" role="group" aria-label="Confirm Done all">
            Mark all ${n} ${n === 1 ? 'email' : 'emails'} read?
            <button class="sm primary" @click=${this._doneAll}>Mark all read</button>
            <button class="sm quiet" @click=${() => { this._all = null; }}>Cancel</button></div>`;
        return html`<div class="all"><button class="sm" ?disabled=${this._doing || this._hiding.size}
            @click=${() => { this._all = 'confirm'; }}>Done all (${n})</button></div>`;
    }

    /** An email: its subject (a link to it in Gmail), who sent it, and with several accounts, which one it came to. */
    _email(a, d, canDo, many) {
        const account = a.email, k = key(account, d);
        const href = gmail(a, d);
        const subject = (d.subject || '(no subject)').replace(/\s+/g, ' ');
        return html`<li>
            <span class="text">
                ${href ? html`<a class="subject" href=${href} target="_blank" rel="noopener">${subject}</a>`
                    : html`<span class="subject">${subject}</span>`}
                <span class="from">${d.from}${many ? html` <span class="to">to ${account}</span>` : ''}</span>
                <span class="why">${d.reason}</span>
            </span>
            ${canDo && d.messageId ? html`<span class="acts">
                <button class="sm" aria-expanded=${this._replying === k ? 'true' : 'false'}
                    title="Claude suggests a reply for you to edit; nothing is sent until you click Send and confirm it"
                    @click=${() => { this._replying = this._replying === k ? null : k; }}>Reply</button>
                <button class="sm" ?disabled=${this._all?.of}
                title="Marks the thread read in ${account}" @click=${() => this._done(account, d)}>
                ${this._doing === k ? spinner : ''}Done</button></span>` : ''}
            ${this._replying === k ? html`<elf-reply class="reply"
                .suggest=${() => elf.act('draft-reply', { account, messageId: d.messageId })}
                .options=${[{ key: 'all', label: 'Reply all', hint: "Also to the email's other recipients, as Cc" }]}
                .send=${(draft, text, o) => elf.act('send-reply', { account, messageId: d.messageId, all: !!o.all,
                    to: draft.where + (o.all && draft.cc ? `, cc ${draft.cc}` : ''), content: text })}
                .findOut=${(text, repo) => elf.act('find-out', { account, messageId: d.messageId, content: text, repo })}
                @reply-closed=${() => { this._replying = null; }}></elf-reply>` : ''}
        </li>`;
    }

    /**
     * One list across your accounts: what needs you, then what you marked Done and what the triage marked read. Each
     * email says which account it came to when there's more than one; an account it couldn't read says why.
     */
    _triage() {
        const accounts = this._accounts ?? [];
        if (!accounts.length) return html`<p class="muted">This run had no accounts to sort.</p>`;
        const many = accounts.length > 1;
        const all = accounts.flatMap(a => a.decisions.map(d => ({ a, d })));
        const kept = all.filter(x => x.d.decision !== 'MARK_READ' && !this._isDone(x.a.email, x.d));
        const doneList = all.filter(x => x.d.decision !== 'MARK_READ' && this._isDone(x.a.email, x.d));
        const read = all.filter(x => x.d.decision === 'MARK_READ');
        const failed = accounts.filter(a => a.error);
        const labelled = read.some(x => (x.a.where ?? 'gmail') === 'gmail');
        return html`
            <section aria-label="Your inbox">
                <div class="account">
                    ${accounts.map(a => {
                        const need = kept.filter(x => x.a === a).length;
                        return html`<span class="chip ${a.error ? 'bad' : need ? 'warn' : 'ok'}">${a.email}: ${a.error
                            ? "couldn't read it" : `${need} need you`}</span>`;
                    })}
                    <span class="chip">${read.length} marked read</span>
                </div>
                ${failed.map(a => html`<p class="error" role="alert">Couldn't read ${a.email}: ${a.error}</p>`)}
                <div class="panel">
                    ${kept.length ? html`<ul>${kept.map(x => this._email(x.a, x.d, true, many))}</ul>`
                        : html`<p class="empty">${doneList.length ? 'All done.' : `Nothing in ${many ? 'your inboxes' : 'your inbox'} needs you.`}</p>`}
                </div>
                ${doneList.length ? html`<details class="panel"><summary>Done (${doneList.length})</summary>
                    <ul>${doneList.map(x => this._email(x.a, x.d, false, many))}</ul></details>` : ''}
                ${read.length ? html`<details class="panel"><summary>Marked read${labelled ? ' (and labelled, at Gmail)' : ''} (${read.length})</summary>
                    <ul>${read.map(x => this._email(x.a, x.d, false, many))}</ul></details>` : ''}
            </section>`;
    }

    render() {
        const r = this._run;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Mail</h1>
                    <p class="sub">
                        ${r ? html`Sorted ${runTime(r.at)}${r.data?.durationMs ? `, took ${took(r.data.durationMs)}` : ''}${this._aiCost(r)}`
                            : this._loading ? '' : 'Not sorted yet.'}
                    </p>
                </div>${settingsButton(null, 'Mail settings')}</div>
                <elf-run-bar command="triage" label="Refresh" .runs=${this._runs ?? []} .picked=${this._picked}
                    note="Goes through your unread mail in each account: noise is marked read, the rest stays unread and is listed here with the reason."
                    @run-picked=${e => { this._picked = e.detail; this._load(); }}
                    @run-finished=${() => { this._picked = null; this._load(); }}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${r ? html`${this._allBar()}${this._triage()}` : ''}
            </div>
        `;
    }
}

/** The dashboard card: how many emails need you in the newest triage. */
class MailCard extends LitElement {

    static properties = { _run: { state: true }, _accounts: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._run = await run(await runs(), null);
            this._accounts = withDone(this._run?.data, await done());
            this._state = await elf.state();
        } catch {
            this._run = null;
        }
        this._loaded = true;
        elf.card({ attention: this._need() > 0 });
    }

    _need() {
        return (this._accounts ?? []).flatMap(a => a.decisions).filter(d => d.decision !== 'MARK_READ' && !d.doneAt).length;
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const r = this._run;
        const running = this._state?.running?.triage;
        const failed = this._state?.failed?.triage;
        let say;
        if (!r) say = running ? 'Sorting your inbox for the first time.' : 'Not sorted yet. Refresh it from its tab.';
        else {
            const decisions = this._accounts.flatMap(a => a.decisions);
            const need = this._need();
            const read = decisions.filter(d => d.decision === 'MARK_READ').length;
            say = !decisions.length ? 'No new email in that run.'
                : need ? html`<a href="#">${count(need, 'email')}</a> ${need === 1 ? 'needs' : 'need'} you${read ? html`, and ${read} ${read === 1 ? 'was' : 'were'} marked read` : ''}.`
                : `Nothing in your inbox needs you; ${count(read, 'email')} ${read === 1 ? 'was' : 'were'} marked read.`;
        }
        return html`<p class="say">${say}</p>
            <div class="meta">${r ? html`<span>Sorted ${runTime(r.at)}${isToday(r.at) ? '' : ', not today yet'}</span>` : ''}${running
                ? html`<span class="running" role="status">${spinner}Running, started ${ago(running)}</span>`
                : failed ? html`<span class="error">The last run failed</span>` : ''}</div>`;
    }
}

customElements.define('mail-tab', MailTab);
customElements.define('mail-card', MailCard);
if (elf.view === 'settings') {
    // Your accounts, then how you write, at the top of the desk's Settings pop-up for Mail.
    await Promise.all([import('./accounts.js'), import('./style.js')]);
    const style = document.createElement('elf-style');
    style.what = 'sent emails';
    document.body.append(document.createElement('mail-accounts'), style);
} else {
    document.body.append(document.createElement(elf.view === 'card' ? 'mail-card' : 'mail-tab'));
}
