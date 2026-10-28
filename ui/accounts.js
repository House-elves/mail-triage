// The accounts Mail sorts, at the top of its Settings pop-up, each in a colour of its own, and Add account: a few
// steps that ask where the mail is, then only what that one needs. A Gmail with an app password, or Sign in to Google
// (Gmail, Google Workspace) or Microsoft (Outlook, Office 365) the way Thunderbird and Evolution do: you sign in at
// Google's or Microsoft's own page. The Google client or Microsoft app signing in needs is asked for the first time.
import { LitElement, html, css, shared, spinner, elf } from '/elf-kit/1/kit.js';

/** The colours to pick from, the first ones Calendar's too. */
export const PALETTE = ['#3b6fd8', '#c2410c', '#2f855a', '#8b5cf6', '#b45309', '#0e7490', '#be185d', '#4d7c0f', '#6d28d9', '#a16207'];

/** An account's colour: the one picked for it, else one by its place in the list. */
export const colourOf = (accounts, email) => {
    const i = (accounts ?? []).findIndex(a => a.email === email);
    return accounts?.[i]?.colour || PALETTE[Math.max(0, i) % PALETTE.length];
};

const HOW = { gmail: 'Gmail, app password', google: 'Google, signed in', microsoft: 'Outlook, signed in', graph: 'Outlook, signed in' };

const README = 'https://github.com/House-elves/mail-triage#your-accounts';

/** Where the mail is, the first thing Add account asks. */
const PROVIDERS = {
    gmail: { label: 'Gmail', sub: 'With an app password from your Google account' },
    google: { label: 'Google', sub: 'Gmail or Google Workspace: sign in at Google' },
    microsoft: { label: 'Microsoft', sub: 'Outlook, Office 365 or Outlook.com: sign in at Microsoft' },
};

class MailAccounts extends LitElement {

    static properties = {
        _list: { state: true },      // [{email, kind, where, colour}]
        _apps: { state: true },      // {microsoft: {app, tenant, redirect}, google: {app, secret}}
        _step: { state: true },      // null, provider, gmail, g-app, g-signin, ms-app, ms-signin
        _signin: { state: true },    // {url, code, paste}
        _evo: { state: true },
        _org: { state: true },
        _hue: { state: true },
        _recolour: { state: true },
        _busy: { state: true },
        _said: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; margin-bottom: 28px; }
        section { display: grid; gap: 10px; }
        h2 { font-size: 18px; font-weight: 600; margin: 0; }
        .hint, .help { font-size: 13px; color: var(--muted); margin: 0; max-width: 44rem; }
        ul.list { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
        ul.list li { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: center; }
        li .email { font-weight: 500; overflow-wrap: anywhere; }
        li .how { font-size: 13px; color: var(--muted); }
        li .spacer { flex: 1; }
        .row { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
        .dot { width: 14px; height: 14px; border-radius: 50%; display: inline-block; flex: none; }
        .dot-btn { padding: 4px; border: 0; background: none; border-radius: 50%; line-height: 0; cursor: pointer; }
        .swatches { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
        .swatches button { width: 24px; height: 24px; padding: 0; border-radius: 50%; border: 2px solid transparent; }
        .swatches button[aria-pressed="true"] { border-color: var(--ink); box-shadow: inset 0 0 0 2px var(--surface); }
        .swatches input[type="color"] { width: 30px; height: 26px; padding: 0; border: 0; background: none; cursor: pointer; }
        li.recolour { padding-left: 26px; }
        .wizard { display: grid; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: var(--r-control, 8px); }
        .wizard h3 { margin: 0; font-size: 15px; font-weight: 600; }
        .wizard h3 span { color: var(--muted); font-weight: normal; }
        .choices { display: grid; grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr)); gap: 8px; }
        .choices button { display: flex; flex-direction: column; align-items: flex-start; justify-content: flex-start; gap: 2px;
            text-align: left; padding: 10px 12px; height: auto; white-space: normal; }
        .choices button b { font-weight: 600; }
        .choices button span { font-size: 13px; color: var(--muted); font-weight: normal; }
        form { display: grid; gap: 10px; }
        .fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; align-items: end; }
        label { display: grid; gap: 4px; font-size: 13px; color: var(--muted); }
        label.check { display: flex; align-items: center; gap: 8px; font-size: 14px; color: inherit; cursor: pointer; }
        input:not([type="checkbox"]):not([type="color"]) { width: 100%; box-sizing: border-box; }
        .code { display: grid; gap: 6px; font-size: 14px; }
        .code p { margin: 0; }
        .code b { font: 600 20px var(--font-code); letter-spacing: 0.08em; user-select: all; }
        .app { font-family: var(--font-code); font-size: 13px; }
        details { font-size: 13px; color: var(--muted); }
        summary { cursor: pointer; color: var(--accent); }
        .where-from { display: grid; gap: 8px; margin: 8px 0 0; padding-left: 18px; max-width: 44rem; }
        .note { font-size: 13px; color: var(--muted); }
        .error { margin: 0; }
        @media (max-width: 560px) { .fields { grid-template-columns: 1fr; } }
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

    /** Asks for an action; its answer always has the accounts and apps as they are now. Null when it failed or you said no. */
    async _act(name, params = {}) {
        this._error = null;
        this._busy = true;
        try {
            const a = await elf.act(name, params);
            if (a?.accounts) this._list = a.accounts;
            if (a?.apps) this._apps = a.apps;
            return a ?? {};
        } catch (e) {
            if (e.message !== 'cancelled') this._error = e.message;
            this._list ??= [];
            return null;
        } finally {
            this._busy = false;
        }
    }

    // ---- the list ----

    async _remove(a) {
        this._said = null;
        const r = await this._act('sign-out', { account: a.email });
        if (r?.removed) this._said = `Removed ${r.removed}.`;
    }

    async _setColour(email, value) {
        if (await this._act('set-colour', { account: email, colour: value })) this._recolour = null;
    }

    _swatches(picked, pick) {
        return html`<div class="swatches" role="group" aria-label="Colour">
            ${PALETTE.map(c => html`<button type="button" style="background:${c}" aria-label=${c} aria-pressed=${picked === c}
                @click=${() => pick(c)}></button>`)}
            <input type="color" aria-label="Another colour" title="Another colour" .value=${picked} @change=${e => pick(e.target.value)}>
        </div>`;
    }

    /** The first colour no account has yet, for the next one. */
    _freeColour() {
        const used = new Set((this._list ?? []).map(a => colourOf(this._list, a.email)));
        return PALETTE.find(c => !used.has(c)) ?? PALETTE[(this._list?.length ?? 0) % PALETTE.length];
    }

    // ---- Add account ----

    _go(step) {
        clearTimeout(this._poll);
        this._signin = null;
        this._error = null;
        this._said = null;
        if (step === 'provider' || !this._step) this._hue = this._freeColour();
        this._step = step;
        if (step === 'ms-app') {
            this._org = null;
            this._act('microsoft-evolution').then(r => { this._evo = r?.evolution ?? []; });
        }
    }

    _back() {
        const s = this._step;
        this._go(s === 'provider' ? null : s === 'g-signin' && this._apps?.google?.app ? 'provider'
            : s === 'ms-signin' && this._apps?.microsoft?.app ? 'provider' : s === 'g-signin' ? 'g-app' : s === 'ms-signin' ? 'ms-app' : 'provider');
    }

    _pickProvider(k) {
        const apps = this._apps ?? {};
        this._go(k === 'gmail' ? 'gmail'
            : k === 'google' ? (apps.google?.app && apps.google?.secret ? 'g-signin' : 'g-app')
            : (apps.microsoft?.app ? 'ms-signin' : 'ms-app'));
    }

    /** Added: Add account closes, gives it the colour picked, and says so. */
    async _added(email) {
        clearTimeout(this._poll);
        if (email && this._hue) await this._act('set-colour', { account: email, colour: this._hue });
        this._step = null;
        this._signin = null;
        this._said = `Added ${email}. Refresh the Mail tab to sort it.`;
    }

    async _addGmail(ev) {
        ev.preventDefault();
        const f = ev.target;
        const email = f.email.value.trim(), password = f.password.value.trim();
        if (!email || !password) {
            this._error = 'Give the Gmail address and its app password.';
            return;
        }
        const r = await this._act('add-gmail', { email, password });
        if (r?.added) await this._added(r.added);
    }

    async _setMicrosoftApp(ev) {
        ev.preventDefault();
        const f = ev.target, org = f.org.checked;
        const params = { app: f.app.value.trim(), tenant: org ? f.tenant.value.trim() : '', redirect: org ? f.redirect.value.trim() : '' };
        if (!params.app) {
            this._error = 'Paste the app ID first.';
            return;
        }
        if (org && !params.tenant) {
            this._error = "Give your organisation's Directory (tenant) ID or domain, or untick the box.";
            return;
        }
        if (await this._act('microsoft-app', params)) this._go('ms-signin');
    }

    async _useEvolution(a) {
        if (await this._act('microsoft-app', { app: a.app, tenant: a.tenant, redirect: a.redirect })) this._go('ms-signin');
    }

    async _setGoogleApp(ev) {
        ev.preventDefault();
        const f = ev.target;
        const params = { app: f.app.value.trim(), secret: f.secret.value.trim() };
        if (!params.app) {
            this._error = 'Paste the client ID first.';
            return;
        }
        if (await this._act('google-app', params)) this._go('g-signin');
    }

    /**
     * Signs in: Microsoft shows a code to type at its page, Google opens its own page and comes back to Mail on this
     * machine. Either way it then asks every few seconds whether you have, until the account is added. An app with a
     * return address has you paste where its page lands instead.
     */
    async _signIn(who) {
        clearTimeout(this._poll);
        this._signin = null;
        const a = await this._act(`${who}-sign-in`);
        if (!a) return;
        // Only Google's or Microsoft's own sign-in page, as the script made it.
        const ok = who === 'google' ? /^https:\/\/accounts\.google\.com\//.test(a.url ?? '')
            : /^https:\/\/((www\.)?microsoft\.com|login\.microsoftonline\.com)\//.test(a.url ?? '');
        if (!ok) {
            this._error = 'That sign-in page isn\'t Google\'s or Microsoft\'s, so it isn\'t shown.';
            return;
        }
        this._signin = { url: a.url, code: a.code, paste: !!a.paste };
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
            if (r?.added) await this._added(r.added);
        };
        this._poll = setTimeout(check, wait);
    }

    /** The address Microsoft's page landed on, its one-time code in it. */
    async _finish(ev) {
        ev.preventDefault();
        const address = new FormData(ev.target).get('address')?.trim();
        if (!address) return;
        const r = await this._act('microsoft-finish', { address });
        if (r?.added) await this._added(r.added);
    }

    _gmail() {
        return html`<form @submit=${this._addGmail}>
            <p class="help">Gmail lets Mail in with an app password, made for it alone: at
                <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noopener">myaccount.google.com/apppasswords</a>
                (it needs 2-Step Verification on), give it a name like Mail and copy the 16 letters. Your own password won't
                do. A work Gmail (Google Workspace) that won't make app passwords: go Back and pick Google.</p>
            <div class="fields">
                <label>Gmail address<input name="email" type="email" maxlength="320" autocomplete="off" placeholder="Your Gmail address"></label>
                <label>App password<input name="password" type="password" maxlength="19" autocomplete="off" placeholder="abcd efgh ijkl mnop"></label>
            </div>
            <div class="row"><button type="submit" class="primary" ?disabled=${this._busy}>${this._busy ? spinner : ''}Add account</button></div>
        </form>`;
    }

    _gApp() {
        const g = this._apps?.google ?? {};
        return html`<form @submit=${this._setGoogleApp}>
            <p class="help">Google only lets a client it knows read mail, so Mail signs in as one of yours: a Desktop app's
                client ID and secret from Google Cloud. Mail keeps them for your next Google account too.</p>
            <div class="fields">
                <label>Client ID<input name="app" type="text" maxlength="120" autocomplete="off" spellcheck="false" .value=${g.app ?? ''}
                    placeholder="1234-abc….apps.googleusercontent.com"></label>
                <label>Client secret<input name="secret" type="password" maxlength="100" autocomplete="off"
                    placeholder=${g.secret ? 'Set: leave empty to keep it' : 'GOCSPX-…'}></label>
            </div>
            <details>
                <summary>Where do I find these?</summary>
                <ol class="where-from">
                    <li>At <a href="https://console.cloud.google.com/projectcreate" target="_blank" rel="noopener">Google Cloud</a>,
                        make a project (any name), signed in with any Google account.</li>
                    <li>Under APIs &amp; Services, Library, find Gmail API and click Enable.</li>
                    <li>Under APIs &amp; Services, OAuth consent screen: External, your app's name and your email. Then Publish
                        app, so it's In production: an app left in Testing has Google sign you out every 7 days. Signing in
                        then warns that Google hasn't verified the app: it's yours, so click Advanced and go on.</li>
                    <li>Under APIs &amp; Services, Credentials: Create credentials, OAuth client ID, Desktop app. Copy its
                        Client ID and Client secret here.</li>
                </ol>
                <p class="help">A work account's admin may still have to allow the client. If Google says access is
                    blocked, ask IT to allow its client ID. <a href=${README} target="_blank" rel="noopener">The README</a> has more.</p>
            </details>
            <div class="row"><button type="submit" class="primary" ?disabled=${this._busy}>${this._busy ? spinner : ''}Next</button></div>
        </form>`;
    }

    _gSignIn() {
        const s = this._signin, g = this._apps?.google ?? {};
        if (s) return html`<div class="code" role="status">
            <p><a href=${s.url} target="_blank" rel="noopener">Open Google's sign-in</a>, pick your account and allow Mail to
                read, mark and send your mail. Google sends you back to Mail on this machine.</p>
            <p class="note">${spinner}Waiting for you to sign in, up to 5 minutes.</p>
        </div>`;
        return html`
            <p class="help">Signs in as Google client <span class="app">${g.app}</span>.
                <button type="button" class="sm quiet" @click=${() => this._go('g-app')}>Change client</button></p>
            <div class="row"><button class="primary" ?disabled=${this._busy} @click=${() => this._signIn('google')}>
                ${this._busy ? spinner : ''}Sign in to Google</button></div>`;
    }

    _msApp() {
        const ms = this._apps?.microsoft ?? {};
        const org = this._org ?? !!(ms.tenant || ms.redirect);
        const evo = this._evo ?? [];
        return html`
            ${evo.length ? html`<div class="code">
                <p>Evolution on this machine signs in to Microsoft with your organisation's own app. Mail can use the same one.</p>
                <div class="row">${evo.map(a => html`<button class="primary" ?disabled=${this._busy} @click=${() => this._useEvolution(a)}>
                    Use Evolution's app${evo.length > 1 && a.account ? ` (${a.account})` : ''}</button>`)}</div>
                <p class="help">Or give one yourself:</p>
            </div>` : ''}
            <form @submit=${this._setMicrosoftApp}>
                <p class="help">Microsoft only lets an app it knows read mail, so Mail signs in as one. Mail keeps it for your
                    next Microsoft account too, and Calendar can use the same one.</p>
                <label>App ID<input name="app" type="text" maxlength="36" autocomplete="off" spellcheck="false" .value=${ms.app ?? ''}
                    placeholder="1a2b3c4d-1a2b-1a2b-1a2b-1a2b3c4d5e6f"></label>
                <label class="check"><input name="org" type="checkbox" .checked=${org} @change=${e => { this._org = e.target.checked; }}>
                    It's my organisation's own app, like the one Evolution uses</label>
                ${org ? html`<div class="fields">
                    <label>Organisation<input name="tenant" type="text" maxlength="100" autocomplete="off" spellcheck="false"
                        .value=${ms.tenant ?? ''} placeholder="Directory (tenant) ID or contoso.com"></label>
                    <label>Return address<input name="redirect" type="url" maxlength="400" autocomplete="off" spellcheck="false"
                        .value=${ms.redirect ?? ''} placeholder="https://login.microsoftonline.com/common/oauth2/nativeclient"></label>
                </div>` : ''}
                <details>
                    <summary>Where do I find these?</summary>
                    <ul class="where-from">
                        <li><b>Your organisation's own app</b>, when it allows only that one (Microsoft then says "Need admin
                            approval" for other apps). Ask IT for the Application (client) ID, Directory (tenant) ID and
                            redirect URI it gave Evolution or Thunderbird. Or copy them from Evolution on your work computer:
                            Edit, Accounts, your work account, Edit, then under Receiving Email, Override Microsoft 365 (or
                            Office365) OAuth2 settings: Tenant, Application ID and Redirect URI. An empty Redirect URI there
                            means https://login.microsoftonline.com/common/oauth2/nativeclient.</li>
                        <li><b>An app of your own</b>, when your organisation lets you consent to apps: register one in the
                            Microsoft Entra admin center and use its Application (client) ID. Leave the box unticked.
                            <a href=${README} target="_blank" rel="noopener">The README says how</a>.</li>
                    </ul>
                </details>
                <div class="row"><button type="submit" class=${evo.length ? '' : 'primary'} ?disabled=${this._busy}>${this._busy ? spinner : ''}Next</button></div>
            </form>`;
    }

    _msSignIn() {
        const s = this._signin, ms = this._apps?.microsoft ?? {};
        if (s?.paste) return html`<div class="code" role="status">
            <p><a href=${s.url} target="_blank" rel="noopener">Open Microsoft's sign-in</a> and sign in to your work account.
                You land on a blank page: copy its whole address from the address bar and paste it here straight away: the
                code in it only lasts a few minutes.</p>
            <form @submit=${this._finish}>
                <input name="address" type="url" required autocomplete="off" spellcheck="false" aria-label="The blank page's address"
                    placeholder="https://…?code=…">
                <div class="row"><button class="primary" ?disabled=${this._busy}>${this._busy ? spinner : ''}Finish signing in</button></div>
            </form>
        </div>`;
        if (s) return html`<div class="code" role="status">
            <p>Open <a href=${s.url} target="_blank" rel="noopener">${s.url}</a>, type this code and sign in to your work
                or school account:</p>
            <p><b>${s.code}</b></p>
            <p class="note">${spinner}Waiting for you to sign in.</p>
        </div>`;
        return html`
            <p class="help">Signs in as app <span class="app">${ms.app}</span>${ms.tenant ? ' of your organisation' : ''}.
                <button type="button" class="sm quiet" @click=${() => this._go('ms-app')}>Change app</button></p>
            <div class="row"><button class="primary" ?disabled=${this._busy} @click=${() => this._signIn('microsoft')}>
                ${this._busy ? spinner : ''}Sign in to Microsoft</button></div>`;
    }

    _wizard() {
        const s = this._step;
        const where = { gmail: 'Gmail, app password', 'g-app': 'Google, your Google client', 'g-signin': 'Google, sign in',
            'ms-app': 'Microsoft, the app it signs in as', 'ms-signin': 'Microsoft, sign in' }[s];
        return html`<section class="wizard" aria-labelledby="add-h">
            <h3 id="add-h">${s === 'provider' ? 'Where is the mail?' : html`Add an account <span>from ${where}</span>`}</h3>
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${['gmail', 'g-signin', 'ms-signin'].includes(s) && !this._signin ? html`<div class="row"><span class="help">Colour</span>
                ${this._swatches(this._hue, v => { this._hue = v; })}</div>` : ''}
            ${s === 'provider' ? html`<div class="choices">${Object.entries(PROVIDERS).map(([k, p]) => html`
                <button @click=${() => this._pickProvider(k)}><b>${p.label}</b><span>${p.sub}</span></button>`)}</div>`
                : s === 'gmail' ? this._gmail() : s === 'g-app' ? this._gApp() : s === 'g-signin' ? this._gSignIn()
                : s === 'ms-app' ? this._msApp() : this._msSignIn()}
            <div class="row">
                ${s !== 'provider' ? html`<button class="sm quiet" @click=${this._back}>Back</button>` : ''}
                <button class="sm quiet" @click=${() => this._go(null)}>Cancel</button>
            </div>
        </section>`;
    }

    render() {
        const list = this._list;
        return html`<section aria-labelledby="accounts-h">
            <h2 id="accounts-h">Your accounts</h2>
            ${!list ? html`<div class="skeleton"></div>` : list.length ? html`<ul class="list">${list.map(a => html`<li>
                <button class="dot-btn" aria-label="Colour of ${a.email}" title="Change its colour" aria-expanded=${this._recolour === a.email}
                    @click=${() => { this._recolour = this._recolour === a.email ? null : a.email; }}><span class="dot"
                    style="background:${colourOf(list, a.email)}"></span></button>
                <span class="email">${a.email}</span><span class="how">${HOW[a.kind] ?? a.kind}</span><span class="spacer"></span>
                <button class="sm quiet" ?disabled=${this._busy || !!this._step} @click=${() => this._remove(a)}>Remove</button></li>
                ${this._recolour === a.email ? html`<li class="recolour">${this._swatches(colourOf(list, a.email), v => this._setColour(a.email, v))}</li>` : ''}`)}</ul>`
                : html`<p class="hint">No accounts yet. Add account to put your first one here.</p>`}
            ${this._said ? html`<p class="note" role="status">${this._said}</p>` : ''}
            ${this._step ? this._wizard() : html`
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                <div class="row"><button class="primary" ?disabled=${!list} @click=${() => this._go('provider')}>Add account</button></div>`}
        </section>`;
    }
}

customElements.define('mail-accounts', MailAccounts);
