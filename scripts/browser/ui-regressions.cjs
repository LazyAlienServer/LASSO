const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const resources = path.resolve(__dirname, '../../las-sso-server/src/main/resources');
const templates = {
  '/admin/users': 'admin-users.html',
  '/admin/clients': 'admin-clients.html',
  '/account/recovery-codes': 'recovery-codes.html',
  '/register': 'register.html',
};
const fakeCode = 'LAS-ADMIN-2345-6789-ABCD-EFGH';
const fakeSecret = 'fixture-client-secret-not-a-credential';

async function main() {
  const server = http.createServer((request, response) => {
    const route = new URL(request.url, 'http://localhost').pathname;
    let file;
    if (templates[route]) file = path.join(resources, 'templates', templates[route]);
    else if (/^\/assets\/[A-Za-z0-9.-]+$/.test(route)) file = path.join(resources, 'static', route.slice(1));
    if (!file || !fs.existsSync(file)) { response.writeHead(404); response.end(); return; }
    response.setHeader('Content-Type', file.endsWith('.html') ? 'text/html' : file.endsWith('.css') ? 'text/css' : 'text/javascript');
    let contents = fs.readFileSync(file, 'utf8');
    if (file.endsWith('.html')) {
      // Source templates intentionally lack a server-side model in this DOM-only fixture.
      contents = contents.replace(/<script type="module"[^>]*><\/script>/g, '');
    }
    response.end(contents);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const channel = process.env.LAS_UI_BROWSER_CHANNEL || 'msedge';
  let browser;
  let context;
  const errors = [];
  try {
    browser = await chromium.launch(channel === 'chromium' ? { headless: true } : { channel, headless: true });
    context = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'], acceptDownloads: true });
    context.on('page', page => page.on('pageerror', error => errors.push(error.name)));
    let available = true;
    await context.route('**/api/**', async route => {
      const request = route.request();
      const url = new URL(request.url()).pathname;
      let body = {};
      let status = 200;
      if (url === '/api/v1/csrf') body = { headerName: 'X-CSRF-TOKEN', token: 'fixture' };
      else if (url === '/api/v1/captcha/challenges') body = { challengeId: 'fixture', parameters: {}, signature: 'fixture' };
      else if (url.endsWith('/recovery-credentials')) body = { secretsAvailable: available, credential: available ? fakeCode : null };
      else if (url === '/api/v1/admin/clients') body = { clientId: 'fixture-client', clientSecret: fakeSecret };
      else if (url === '/api/v1/me/reauthentication') status = 204;
      else if (url === '/api/v1/me/recovery-codes/status') body = { availableCount: 10 };
      else if (url === '/api/v1/me/recovery-codes/rotate') body = { recoveryCodes: ['LAS-2345-6789-ABCD-EFGH'] };
      else if (url === '/api/v1/registrations') { status = 201; body = { recoveryCodes: Array(10).fill('LAS-2345-6789-ABCD-EFGH') }; }
      await route.fulfill({ status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(body) });
    });
    const page = await context.newPage();
    await page.goto(origin + '/admin/users');
    await page.locator('[data-action=recovery-credentials]').click();
    await page.locator('#admin-action-form [name=reason]').fill('fixture');
    await page.locator('#admin-action-form [name=password]').fill('fixture-password');
    await page.locator('#admin-dialog-submit').click();
    await page.locator('#admin-credential-dialog').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#admin-credential-value').textContent(), fakeCode);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#admin-credential-value').textContent(), '');
    available = false;
    await page.locator('[data-action=recovery-credentials]').click();
    await page.locator('#admin-action-form [name=reason]').fill('fixture');
    await page.locator('#admin-action-form [name=password]').fill('fixture-password');
    await page.locator('#admin-dialog-submit').click();
    await page.locator('#admin-dialog-error').filter({ hasText: '不能再次显示' }).waitFor();
    assert.ok(await page.locator('#admin-action-dialog').isVisible());
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#admin-action-form [name=password]').inputValue(), '');
    console.log('PASS credential close erasure, visible idempotent error and password cleanup');

    await page.goto(origin + '/admin/clients');
    await page.locator('#open-create-client').click();
    for (const [name, value] of Object.entries({ clientName: 'Fixture', redirectUris: 'https://fixture.invalid/callback', resourceAudiences: 'urn:fixture', reason: 'fixture', password: 'fixture-password' })) {
      await page.locator(`#client-create-form [name=${name}]`).fill(value);
    }
    await page.locator('#client-create-submit').click();
    await page.locator('#client-secret-dialog').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#client-secret-value').textContent(), fakeSecret);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#client-secret-value').textContent(), '');
    console.log('PASS client Secret erased by native Escape');

    await context.addInitScript(() => {
      class FixtureCaptcha extends HTMLElement {
        configure() {}
        reset() {}
        verify() { this.value = 'fixture'; this.dispatchEvent(new CustomEvent('statechange', { detail: { state: 'verified' } })); }
      }
      customElements.define('altcha-widget', FixtureCaptcha);
    });
    await page.goto(origin + '/register');
    await page.locator('#captcha-frame[aria-busy=false]').waitFor();
    await page.locator('#username').fill('FixtureUser');
    await page.locator('#displayName').fill('Fixture');
    await page.locator('#password').fill('fixture-password');
    await page.locator('#agreement').check();
    await page.evaluate(() => document.querySelector('altcha-widget').verify());
    await page.locator('#register-submit').click();
    await page.locator('#registration-success').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#password').inputValue(), '');
    await page.locator('#copy-codes').click();
    await page.locator('#recovery-action-status[data-state=success]').waitFor();
    assert.equal(await page.locator('#copy-codes').textContent(), '已复制');
    assert.equal((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n'), Array(10).fill('LAS-2345-6789-ABCD-EFGH').join('\n'));
    await page.evaluate(() => navigator.clipboard.writeText(''));
    await page.locator('#codes-saved').check();
    await page.goto(origin + '/admin/users');
    console.log('PASS registration clipboard feedback and hidden password reset');

    await page.goto(origin + '/account/recovery-codes');
    await page.locator('#rotate-confirmation').check();
    await page.locator('#rotate-recovery-codes').click();
    await page.locator('#rotated-recovery-code-list li').waitFor();
    await page.locator('#rotated-codes-saved').check();
    await page.goto(origin + '/admin/users');
    await page.goBack();
    assert.equal(await page.locator('#rotated-recovery-code-list li').count(), 0);
    console.log('PASS rotated recovery codes cannot reappear through page history');
    assert.deepEqual(errors, []);
  } finally {
    if (context) await context.close();
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
}

main().catch(error => {
  // Never print request headers or response/secret-bearing Playwright details.
  console.error('UI regression failed:', error.name);
  process.exitCode = 1;
});
