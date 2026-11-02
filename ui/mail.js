// mail-triage's tab on Elf Desk, and its dashboard card: across your accounts (Gmail, Google Workspace, Outlook), what
// needs you and what the triage marked read. Done marks the thread read in its account, through the desk; Delete moves
// the email to the account's Trash, once you confirm it in the desk's bar.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, took, count, isToday, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';
import { runs, run, done, deleted, aiThisMonth, usd } from './runs.js';
import './reply.js';
import { colourOf } from './accounts.js';


/** The email in Gmail's web page. Outlook's has no link by Message-ID, so its emails have none. */
function gmail(a, d) {
    if (!d.messageId || (a.where ?? 'gmail') !== 'gmail') return null;
    const account = a.email;
    const id = d.messageId.replace(/^<|>$/g, '');
    return `https://mail.google.com/mail/?authuser=${encodeURIComponent(account)}#search/rfc822msgid:${encodeURIComponent(id)}`;
}

/** Look into's verdicts, as the tab says them. */
const VERDICT = { act: 'Needs you to act', reply: 'Needs a reply', wait: 'Nothing yet', nothing: 'Nothing to do' };

/** What Do it did with each step. */
const DID = { done: 'Done', 'needs-you': 'Needs you', couldnt: "Couldn't" };

/** Which email, in which account: the same email can reach two of yours. */
const key = (account, d) => `${account}\n${d.messageId}`;

/** The run's accounts, each decision with when you marked it Done (doneAt); what you deleted is left out. */
function withDone(data, marks, gone = {}) {
    return (data?.triage?.accounts ?? []).map(a => ({
        ...a,
        decisions: (a.decisions ?? []).filter(d => !gone[a.email]?.[d.messageId])
            .map(d => ({ ...d, doneAt: marks[a.email]?.[d.messageId] ?? null })),
    }));
}

class MailTab extends LitElement {

    static properties = {
        _runs: { state: true },
        _run: { state: true },
        _aiMonth: { state: true },   // what Claude cost over this month's runs, or null
        _replying: { state: true },  // the email whose reply is open (key())
        _lookAt: { state: true },    // the email whose Look into is open (key())
        _looks: { state: true },     // key() -> {busy, advice, error, steps, agent}: what the coding agent said about each email
        _accounts: { state: true },
        _colours: { state: true },   // the accounts as Settings has them now, for their colours
        _picked: { state: true },
        _doing: { state: true },
        _deleting: { state: true },  // the email whose Delete is waiting for the desk's bar or its account (key())
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
        li { padding: 11px 18px 11px 15px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 3px 14px; align-items: center;
            border-left: 3px solid transparent; }
        li > .text { display: grid; gap: 3px; min-width: 0; }
        li + li { border-top: 1px solid var(--line); }
        li .acts { display: flex; gap: 8px; }
        li elf-reply.reply, li .look { grid-column: 1 / -1; }
        /* Look into: the coding agent's advice on an email, under it. */
        .look { display: grid; gap: 8px; padding: 12px 16px; margin-top: 10px; border-radius: var(--r-panel);
            border: 1px solid var(--line); background: var(--surface-2); min-width: 0; font-size: 14px; }
        .look p { margin: 0; overflow-wrap: anywhere; }
        .look .verdict { font-weight: 600; display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: baseline; }
        .look .tag { font-size: 12.5px; padding: 1px 8px; border-radius: 999px; }
        .look .tag.act { background: var(--warn-soft); color: var(--ink); }
        .look .tag.reply { background: var(--accent-soft); color: var(--ink); }
        .look .tag.wait { background: var(--idle-soft); color: var(--ink); }
        .look .tag.nothing { background: var(--ok-soft); color: var(--ink); }
        .look .why, .look .cost { color: var(--muted); font-size: 13.5px; }
        .look .caution { padding: 8px 10px; border-radius: var(--r-control); background: var(--bad-soft); }
        .look .acts { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        .look .steps { display: grid; gap: 6px; }
        .look .step { display: grid; grid-template-columns: auto auto minmax(0, 1fr); gap: 8px; align-items: center; }
        .look .step .n { color: var(--muted); font-variant-numeric: tabular-nums; }
        .look .step input[type=text] { font: 14px var(--font); padding: 4px 8px; border: 1px solid transparent;
            border-radius: var(--r-control); background: transparent; color: var(--ink); min-width: 0; }
        .look .step input[type=text]:hover, .look .step input[type=text]:focus { border-color: var(--line); background: var(--bg); }
        .look .step.off input[type=text] { color: var(--muted); text-decoration: line-through; }
        .look .did { display: grid; gap: 8px; padding: 10px 12px; border-radius: var(--r-control); background: var(--bg); }
        .look .did ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; }
        .look .did li { display: grid; grid-template-columns: minmax(0, 1fr); gap: 2px; padding: 0; border: 0; }
        .look .tag.done { background: var(--ok-soft); color: var(--ink); }
        .look .tag.needs-you { background: var(--warn-soft); color: var(--ink); }
        .look .tag.couldnt { background: var(--bad-soft); color: var(--ink); }
        .look .src { color: var(--muted); font-size: 13px; overflow-wrap: anywhere; }
        .subject { font-weight: 500; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.subject:hover { text-decoration: underline; }
        .from, .why { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        /* Claude's sentence or two on what it's about and where it stands, as Zulip's digests. */
        .digest { font-size: 14px; line-height: 1.45; color: var(--ink); overflow-wrap: anywhere; margin-top: 2px; max-width: 70ch; }
        .chip .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; margin-right: 6px; vertical-align: 1px; }
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
        this._looks = {};
        this._queue = Promise.resolve();
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        // Each account's colour as it is now, not as it was at the last run.
        elf.act('accounts', {}).then(r => { if (r?.accounts) this._colours = r.accounts; }).catch(() => {});
        // Do it's agents: their reports as they change.
        this._unwatch = elf.onChange(what => {
            if (what !== 'agents') return;
            for (const [k, l] of Object.entries(this._looks)) if (l.agent?.id) this._readAgent(k);
        });
    }

    disconnectedCallback() {
        super.disconnectedCallback();
        this._unwatch?.();
    }

    /** An account's colour: as Settings has it, else as the run saved it, else by its place. */
    _colour(a) {
        if (this._colours?.some(x => x.email === a.email)) return colourOf(this._colours, a.email);
        return a.colour || colourOf(this._accounts, a.email);
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
        const [marks, gone] = await Promise.all([done(), deleted()]);
        this._accounts = withDone(this._run?.data, marks, gone);
    }

    /**
     * Delete: the desk's bar asks you first; then its account moves the email to the Trash and it leaves the list.
     * Cancel in the bar leaves it as it was.
     */
    async _delete(account, d) {
        const k = key(account, d);
        this._deleting = k;
        this._error = null;
        try {
            await elf.act('delete', { account, messageId: d.messageId });
            await this._marks();
            if (this._replying === k) this._replying = null;
            if (this._lookAt === k) this._lookAt = null;
        } catch (e) {
            if (!e.cancelled) this._error = `${d.subject || 'That email'}: not deleted: ${e.message}`;
        } finally {
            this._deleting = null;
        }
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

    /** Look into opens (asking the coding agent the first time) or closes the advice on an email. */
    _toggleLook(account, d) {
        const k = key(account, d);
        if (this._lookAt === k) {
            this._lookAt = null;
            return;
        }
        this._lookAt = k;
        if (!this._looks[k]) this._look(account, d);
    }

    async _look(account, d) {
        const k = key(account, d);
        this._looks = { ...this._looks, [k]: { busy: true } };
        try {
            const advice = await elf.act('look-into', { account, messageId: d.messageId });
            // Each step ticked, as written: you untick or reword them before Do it.
            const steps = (advice.steps ?? []).map(text => ({ text, on: true }));
            this._looks = { ...this._looks, [k]: { advice, steps } };
        } catch (e) {
            this._looks = { ...this._looks, [k]: { error: e.message } };
        }
    }

    _setLook(k, change) {
        this._looks = { ...this._looks, [k]: { ...this._looks[k], ...change } };
    }

    /** One step's tick or words, on the steps as they are now (not as they were drawn). */
    _setStep(k, i, change) {
        this._setLook(k, { steps: this._looks[k].steps.map((x, j) => j === i ? { ...x, ...change } : x) });
    }

    /**
     * The steps, each with a tick and in words you can change, and Do it: an agent does the ticked ones, as you left
     * them, reading only the web. Then its progress, and what it found or left for you, step by step.
     */
    _stepsPart(k, l) {
        const steps = l.steps ?? [];
        if (!steps.length) return '';
        const ag = l.agent;
        const running = ag && ['queued', 'running'].includes(ag.status);
        const on = steps.filter(s => s.on && s.text.trim());
        return html`
            <div class="steps" role="group" aria-label="Steps">
                ${steps.map((s, i) => html`<div class="step ${s.on ? '' : 'off'}">
                    <input type="checkbox" aria-label="Do step ${i + 1}" .checked=${s.on} ?disabled=${running}
                        @change=${e => this._setStep(k, i, { on: e.target.checked })}>
                    <span class="n">${i + 1}.</span>
                    <input type="text" aria-label="Step ${i + 1}" .value=${s.text} ?disabled=${running} maxlength="400"
                        @input=${e => this._setStep(k, i, { text: e.target.value })}>
                </div>`)}
            </div>
            ${running ? html`<div class="did" role="status">
                <p class="why">${spinner} Doing ${on.length} ${on.length === 1 ? 'step' : 'steps'} on the web. This takes a few minutes.</p>
                <p class="src">${ag.lines?.length ? ag.lines[ag.lines.length - 1] : 'Starting…'}</p>
                <div class="acts"><button class="sm quiet" @click=${() => elf.cancelAgent(ag.id).catch(() => {})}>Stop</button></div>
            </div>` : ag?.status === 'done' ? this._didPart(ag)
            : ag?.status === 'failed' ? html`<p class="error" role="alert">The agent couldn't do it: ${ag.error}</p>`
            : ag?.status === 'cancelled' ? html`<p class="why">Stopped.</p>` : ''}
            ${running ? '' : html`<div class="acts">
                <button class="sm primary" ?disabled=${!on.length || l.starting}
                    title="An agent does the ticked steps, as written here, by searching and reading the web. It never sees the email, and can't sign in, change, send or pay for anything"
                    @click=${() => this._doSteps(k)}>${l.starting ? spinner : ''}${ag ? 'Do it again' : 'Do it'}</button>
                <span class="src">An agent does the ticked steps by reading the web. It's given only these steps, not the email, and changes nothing.</span>
            </div>`}`;
    }

    _didPart(ag) {
        const o = ag.output ?? {};
        return html`<div class="did">
            ${o.summary ? html`<p><b>${o.summary}</b></p>` : ''}
            <ul>${(o.results ?? []).map(r => html`<li>
                <p><span class="tag ${r.status}">${DID[r.status] ?? r.status}</span> ${r.step}</p>
                <p>${r.found}</p>
                ${r.source ? html`<p class="src">${/^https?:\/\//.test(r.source)
                    ? html`<a href=${r.source} target="_blank" rel="noopener noreferrer">${r.source}</a>` : r.source}</p>` : ''}
            </li>`)}</ul>
            ${o.note ? html`<p class="src">${o.note}</p>` : ''}
            ${ag.cost != null ? html`<p class="src">AI ${usd(ag.cost)}.</p>` : ''}
        </div>`;
    }

    async _doSteps(k) {
        const l = this._looks[k];
        const steps = l.steps.filter(s => s.on && s.text.trim()).map(s => s.text.trim().replace(/\s+/g, ' '));
        this._setLook(k, { starting: true, agent: null });
        try {
            const r = await elf.act('do-steps', { steps: steps.join('\n') });
            this._setLook(k, { starting: false, agent: { id: r.agent.id, status: r.agent.status, lines: [] } });
            this._readAgent(k);
        } catch (e) {
            this._setLook(k, { starting: false, agent: { status: 'failed', error: e.message } });
        }
    }

    /** The agent's report as it is now, for whichever email's steps it's doing. */
    async _readAgent(k) {
        const id = this._looks[k]?.agent?.id;
        if (!id) return;
        const a = await elf.agent(id).catch(() => null);
        if (a && this._looks[k]?.agent?.id === id) this._setLook(k, { agent: a });
    }

    /** What the coding agent said: whether you need to do anything, by when, the steps, why, and what to be careful of. */
    _lookPanel(account, d, k) {
        const l = this._looks[k] ?? {};
        if (l.busy) return html`<div class="look" role="status"><p class="why">${spinner} Reading the email and its thread. This takes a little while.</p></div>`;
        const a = l.advice;
        const again = html`<button class="sm quiet" @click=${() => this._look(account, d)}>Look again</button>`;
        const close = html`<button class="sm quiet" @click=${() => { this._lookAt = null; }}>Close</button>`;
        if (!a) return html`<div class="look"><p class="error" role="alert">Couldn't look into it: ${l.error}</p>
            <div class="acts">${again}${close}</div></div>`;
        return html`<div class="look" role="group" aria-label="What to do about it">
            <p class="verdict"><span class="tag ${a.verdict}">${VERDICT[a.verdict] ?? a.verdict}</span>${a.headline}</p>
            ${a.due ? html`<p><b>By</b> ${a.due}</p>` : ''}
            ${this._stepsPart(k, l)}
            ${a.why ? html`<p class="why">${a.why}</p>` : ''}
            ${a.caution ? html`<p class="caution" role="note"><b>Careful:</b> ${a.caution}</p>` : ''}
            <div class="acts">
                ${['act', 'reply'].includes(a.verdict) && this._replying !== k ? html`<button class="sm"
                    @click=${() => { this._replying = k; }}>Suggest a reply</button>` : ''}
                ${again}${close}
            </div>
            <p class="cost">The coding agent's advice, from the email alone: check it before you act on it.${a.cost != null ? ` AI ${usd(a.cost)}.` : ''}</p>
        </div>`;
    }

    /** An email: its subject (a link to it in Gmail), who sent it, and with several accounts, which one it came to. */
    _deleteButton(account, d, k) {
        return html`<button class="sm danger" ?disabled=${this._all?.of || this._deleting || this._hiding.has(k)}
            title="Moves this email to the Trash in ${account}, after you confirm it" @click=${() => this._delete(account, d)}>
            ${this._deleting === k ? spinner : ''}Delete</button>`;
    }

    /** canDo: Reply, Done and Delete, for what needs you; canDelete: Delete alone, for what you marked Done. */
    _email(a, d, canDo, many, canDelete = false) {
        const account = a.email, k = key(account, d);
        const href = gmail(a, d);
        const subject = (d.subject || '(no subject)').replace(/\s+/g, ' ');
        return html`<li style="border-left-color:${this._colour(a)}">
            <span class="text">
                ${href ? html`<a class="subject" href=${href} target="_blank" rel="noopener">${subject}</a>`
                    : html`<span class="subject">${subject}</span>`}
                <span class="from">${d.from}${many ? html` <span class="to">to ${account}</span>` : ''}</span>
                ${d.summary ? html`<span class="digest">${d.summary}</span>` : html`<span class="why">${d.reason}</span>`}
            </span>
            ${canDo && d.messageId ? html`<span class="acts">
                <button class="sm" aria-expanded=${this._replying === k ? 'true' : 'false'}
                    title="Claude suggests a reply for you to edit; nothing is sent until you click Send and confirm it"
                    @click=${() => { this._replying = this._replying === k ? null : k; }}>Reply</button>
                <button class="sm" aria-expanded=${this._lookAt === k ? 'true' : 'false'}
                    title="The coding agent reads the email and its thread and says whether you need to do anything; it only advises"
                    @click=${() => this._toggleLook(account, d)}>Look into</button>
                <button class="sm" ?disabled=${this._all?.of || this._deleting === k}
                title="Marks the thread read in ${account}" @click=${() => this._done(account, d)}>
                ${this._doing === k ? spinner : ''}Done</button>
                ${this._deleteButton(account, d, k)}</span>`
                : canDelete && d.messageId ? html`<span class="acts">${this._deleteButton(account, d, k)}</span>` : ''}
            ${this._lookAt === k ? this._lookPanel(account, d, k) : ''}
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
                        return html`<span class="chip ${a.error ? 'bad' : need ? 'warn' : 'ok'}"><span class="dot"
                            style="background:${this._colour(a)}"></span>${a.email}: ${a.error
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
                    <ul>${doneList.map(x => this._email(x.a, x.d, false, many, true))}</ul></details>` : ''}
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
                    note="Goes through your unread mail in each account: noise is marked read, the rest stays unread and is listed here, each summed up in a sentence or two."
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
            const [marks, gone] = await Promise.all([done(), deleted()]);
            this._accounts = withDone(this._run?.data, marks, gone);
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
