// The accounts Mail sorts, at the top of its Settings pop-up: the Gmail with an app password from the settings below,
// and the accounts you signed in to. Sign in to Google (Gmail, Google Workspace) and Sign in to Microsoft (Outlook,
// Office 365) add one the way Thunderbird and Evolution do: you sign in at Google's or Microsoft's own page.
import { LitElement, html, css, shared, spinner, elf } from '/elf-kit/1/kit.js';

const HOW = { gmail: 'Gmail, app password', google: 'Google, signed in', microsoft: 'Outlook, signed in', graph: 'Outlook, signed in' };

class MailAccounts extends LitElement {

    static properties = {
        _list: { state: true },      // [{email, kind, where}]
        _signin: { state: true },    // {who: 'google' | 'microsoft', url, code, paste}
        _busy: { state: true },
        _said: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; margin-bottom: 28px; }
        section { display: grid; gap: 10px; }
        h2 { font-size: 18px; font-weight: 600; margin: 0; }
        .hint { font-size: 14px; color: var(--muted); margin: 0; max-width: 44rem; }
        ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
        li { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: center; }
        li .email { font-weight: 500; overflow-wrap: anywhere; }
        li .how { font-size: 13px; color: var(--muted); }
        li .spacer { flex: 1; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
        .code { display: grid; gap: 6px; padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--r-control); }
        .code p { margin: 0; font-size: 14px; }
        .code b { font: 600 20px var(--font-code); letter-spacing: 0.08em; }
        .code form { display: flex; flex-wrap: wrap; gap: 8px; }
        .code input { flex: 1 1 18rem; min-width: 0; }
        .note { font-size: 13px; color: var(--muted); }
        .error { margin: 0; }
    `];

    async connectedCallback() {
        super.connectedCallback();
        await this._act('accounts');
    }

    disconnectedCallback() {
        clearTimeout(this._poll);
        this._signin = null;
        super.disconnectedCallback();
    }

    /** Asks for an action; its answer always has the accounts as they are now. Null when it failed or you said no. */
    async _act(name, params = {}) {
        this._error = null;
        try {
            const a = await elf.act(name, params);
            if (a?.accounts) this._list = a.accounts;
            return a ?? {};
        } catch (e) {
            if (e.message !== 'cancelled') this._error = e.message;
            this._list ??= [];
            return null;
        }
    }

    /**
     * Signs in: Microsoft shows a code to type at its page, Google opens its own page and comes back to Mail on this
     * machine. Either way it then asks every few seconds whether you have, until the account is in the list. An app
     * with a return address (Microsoft return address) has you paste where its page lands instead.
     */
    async _signIn(who) {
        clearTimeout(this._poll);
        this._said = null;
        this._busy = who;
        const a = await this._act(`${who}-sign-in`);
        this._busy = null;
        if (!a) return;
        // Only Google's or Microsoft's own sign-in page, as the script made it.
        const ok = who === 'google' ? /^https:\/\/accounts\.google\.com\//.test(a.url ?? '')
            : /^https:\/\/((www\.)?microsoft\.com|login\.microsoftonline\.com)\//.test(a.url ?? '');
        if (!ok) {
            this._error = 'That sign-in page isn\'t Google\'s or Microsoft\'s, so it isn\'t shown.';
            return;
        }
        this._signin = { who, url: a.url, code: a.code, paste: !!a.paste };
        if (a.paste) return;
        const wait = Math.max(3, Math.min(30, a.interval ?? 5)) * 1000;
        const check = async () => {
            if (!this._signin) return;
            const r = await this._act(`${who}-check`);
            if (!this._signin) return;
            if (r?.waiting) {
                this._poll = setTimeout(check, wait);
                return;
            }
            this._signin = null;
            if (r?.added) this._said = `Signed in to ${r.added}. Refresh the Mail tab to sort it.`;
        };
        this._poll = setTimeout(check, wait);
    }

    /** The address Microsoft's page landed on, its one-time code in it. */
    async _finish(ev) {
        ev.preventDefault();
        const address = new FormData(ev.target).get('address')?.trim();
        if (!address) return;
        this._busy = 'microsoft';
        const r = await this._act('microsoft-finish', { address });
        this._busy = null;
        if (r?.added) {
            this._signin = null;
            this._said = `Signed in to ${r.added}. Refresh the Mail tab to sort it.`;
        }
    }

    _cancel() {
        clearTimeout(this._poll);
        this._signin = null;
    }

    async _remove(a) {
        this._said = null;
        const r = await this._act('sign-out', { account: a.email });
        if (r?.removed) this._said = `Removed ${r.removed}.`;
    }

    _signInBox() {
        const s = this._signin;
        if (s.paste) return html`<div class="code" role="status">
            <p><a href=${s.url} target="_blank" rel="noopener">Open Microsoft's sign-in</a> and sign in to your work account.
                You land on a blank page: copy its whole address from the address bar and paste it here straight away: the code in it only lasts a few minutes.</p>
            <form @submit=${this._finish}>
                <input name="address" type="url" required autocomplete="off" spellcheck="false" aria-label="The blank page's address"
                    placeholder="https://…?code=…">
                <button class="sm" ?disabled=${!!this._busy}>${this._busy ? spinner : ''}Finish signing in</button>
            </form>
            <p class="note"><button class="sm quiet" @click=${this._cancel}>Stop</button></p>
        </div>`;
        if (s.who === 'microsoft') return html`<div class="code" role="status">
            <p>Open <a href=${s.url} target="_blank" rel="noopener">${s.url}</a>, type this code and sign in to your work
                or school account:</p>
            <p><b>${s.code}</b></p>
            <p class="note">${spinner}Waiting for you to sign in. <button class="sm quiet" @click=${this._cancel}>Stop waiting</button></p>
        </div>`;
        return html`<div class="code" role="status">
            <p><a href=${s.url} target="_blank" rel="noopener">Open Google's sign-in</a>, pick your account and allow Mail to
                read, mark and send your mail. Google sends you back to Mail on this machine.</p>
            <p class="note">${spinner}Waiting for you to sign in, up to 5 minutes. <button class="sm quiet" @click=${this._cancel}>Stop waiting</button></p>
        </div>`;
    }

    render() {
        const list = this._list;
        return html`<section aria-labelledby="accounts-h">
            <h2 id="accounts-h">Your accounts</h2>
            ${!list ? html`<div class="skeleton"></div>` : list.length ? html`<ul>${list.map(a => html`<li>
                <span class="email">${a.email}</span><span class="how">${HOW[a.kind] ?? a.kind}</span><span class="spacer"></span>
                ${a.kind === 'gmail' ? html`<span class="how">Change it in Gmail account below</span>`
                    : html`<button class="sm quiet" ?disabled=${!!this._busy || !!this._signin} @click=${() => this._remove(a)}>Remove</button>`}
            </li>`)}</ul>` : html`<p class="hint">No accounts yet. Sign in below, or set a Gmail account and app password in the settings below.</p>`}
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${this._said ? html`<p class="note" role="status">${this._said}</p>` : ''}
            ${this._signin ? this._signInBox() : html`<div class="acts">
                <button class="sm" ?disabled=${!!this._busy} title="Opens Google's sign-in page" @click=${() => this._signIn('google')}>
                    ${this._busy === 'google' ? spinner : ''}Sign in to Google</button>
                <button class="sm" ?disabled=${!!this._busy} title="Shows a code to type at Microsoft's sign-in page" @click=${() => this._signIn('microsoft')}>
                    ${this._busy === 'microsoft' ? spinner : ''}Sign in to Microsoft</button>
            </div>`}
            <p class="hint"><b>Gmail</b>: an app password below, or Sign in to Google. <b>Google Workspace</b> (work Gmail)
                that won't make app passwords: Sign in to Google. <b>Outlook</b> and Office 365: Sign in to Microsoft.
                Signing in needs an app of yours that Google or Microsoft knows: its ID goes in <b>Google client ID</b>
                (with its secret) or <b>Microsoft app ID</b> below, and Mail's README says how to make one. An
                organisation's app that won't take a code also needs <b>Microsoft organisation</b> and <b>Microsoft return
                address</b>. A work account's
                admin may have to allow that app first, as they allowed Thunderbird or Evolution.</p>
        </section>`;
    }
}

customElements.define('mail-accounts', MailAccounts);
