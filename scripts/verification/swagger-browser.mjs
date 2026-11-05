// Windows Edge에서 실제 Swagger 화면 전환을 검사한다. 비밀 파일은 읽지 않는다.
// 실행: node scripts/verification/swagger-browser.mjs
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';

const origin = process.env.TASK_VERIFY_ORIGIN || 'http://127.0.0.1:8081';
const executable = process.env.TASK_BROWSER_EXECUTABLE ||
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const profile = path.resolve('build/verification/edge-' + Date.now());
await mkdir(profile, { recursive: true });
const listener = net.createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const browser = spawn(executable, [
  '--headless=new', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--remote-debugging-address=127.0.0.1',
  '--remote-debugging-port=' + port, '--user-data-dir=' + profile, 'about:blank'
], { windowsHide: true, stdio: 'ignore' });
let socket;
let browserError;
browser.on('error', error => { browserError = error; });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(callback) {
  const limit = Date.now() + 20000;
  while (Date.now() < limit) {
    if (browserError) throw browserError;
    try { const result = await callback(); if (result) return result; } catch {}
    await sleep(150);
  }
  throw new Error('브라우저 검증 대기 시간 초과');
}
try {
  const pages = await waitFor(async () => {
    const response = await fetch('http://127.0.0.1:' + port + '/json/list');
    return response.ok && response.json();
  });
  socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  let sequence = 0;
  const pending = new Map();
  const requests = [];
  const responses = [];
  let internalHeaderSent = false;
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Network.requestWillBeSent') {
      // 값이나 헤더는 보관하지 않는다.
      requests.push(message.params.request.url);
      internalHeaderSent ||= Object.keys(message.params.request.headers || {})
        .some(name => name.toLowerCase() === 'x-internal-service-key');
    }
    if (message.method === 'Network.responseReceived') {
      responses.push({ id: message.params.requestId, url: message.params.response.url,
        status: message.params.response.status });
    }
    if (message.id && pending.has(message.id)) {
      const handler = pending.get(message.id);
      pending.delete(message.id);
      clearTimeout(handler.timer);
      if (message.error) handler.reject(new Error(message.error.message));
      else handler.resolve(message.result);
    }
  });
  function command(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP 시간 초과')); }, 10000);
      pending.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ id, method, params }));
    });
  }
  async function evaluate(expression) {
    const result = await command('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error('브라우저 스크립트 검사 실패');
    return result.result.value;
  }
  await command('Network.enable');
  await command('Page.navigate', { url: origin + '/swagger-ui.html' });
  await waitFor(() => evaluate('!!window.ui && window.ui.specSelectors.specJson().get("openapi")'));
  assert.equal(await evaluate('window.ui.getConfigs().supportedSubmitMethods.includes("get")'), true);
  assert.deepEqual(await evaluate('Object.keys(window.ui.specSelectors.specJson().toJS().paths).sort()'),
    ['/api/member/{id}', '/api/members']);
  const block = apiPath => `Array.from(document.querySelectorAll('.opblock')).find(node =>
    node.querySelector('.opblock-summary-path')?.getAttribute('data-path') === ${JSON.stringify(apiPath)})`;
  async function execute(apiPath, memberId, paramName = 'id', prefix = '', expectedStatus = 200) {
    await waitFor(() => evaluate(`!!(${block(apiPath)})`));
    await evaluate(`(${block(apiPath)}).querySelector('.opblock-summary-control').click()`);
    await waitFor(() => evaluate(`!!(${block(apiPath)}).querySelector('.try-out__btn')`));
    await evaluate(`(${block(apiPath)}).querySelector('.try-out__btn').click()`);
    if (memberId) {
      await evaluate(`(() => {
        const row = (${block(apiPath)}).querySelector('tr[data-param-name="${paramName}"]');
        if (!row.querySelector('input:not([type="checkbox"]), textarea')) {
          const add = Array.from(row.querySelectorAll('button')).find(button => /add/i.test(button.textContent));
          if (add) add.click();
        }
      })()`);
      await waitFor(() => evaluate(`!!(${block(apiPath)}).querySelector('tr[data-param-name="${paramName}"] input:not([type="checkbox"]), tr[data-param-name="${paramName}"] textarea')`));
      await evaluate(`(() => {
        const input = (${block(apiPath)}).querySelector('tr[data-param-name="${paramName}"] input:not([type="checkbox"]), tr[data-param-name="${paramName}"] textarea');
        input.focus(); input.select();
      })()`);
      await command('Input.insertText', { text: memberId });
      await waitFor(() => evaluate(`(${block(apiPath)}).querySelector('tr[data-param-name="${paramName}"] input:not([type="checkbox"]), tr[data-param-name="${paramName}"] textarea').value === ${JSON.stringify(memberId)}`));
      // Swagger 입력의 지연된 상태 반영을 기다린 후 실제 실행 버튼을 누른다.
      await waitFor(() => evaluate(`Object.values(window.ui.specSelectors.parameterValues([${JSON.stringify(apiPath)}, 'get']).toJS()).flat(Infinity).includes(${JSON.stringify(memberId)})`));
    }
    const start = responses.length;
    await evaluate(`(${block(apiPath)}).querySelector('.execute').click()`);
    const target = prefix + (memberId ? apiPath.replace('{' + paramName + '}', memberId) : apiPath);
    const response = await waitFor(() => responses.slice(start).find(item => new URL(item.url).pathname === target)).catch(async error => {
      const state = await evaluate(`(() => {
        const node = (${block(apiPath)});
        return { validation: Array.from(node.querySelectorAll('.validation-errors, .errors-wrapper')).map(e => e.textContent),
          inputs: Array.from(node.querySelectorAll('input')).map(e => ({type:e.type, length:e.value.length})),
          executing: !!node.querySelector('.execute[disabled]') };
      })()`);
      console.error('Swagger 실행 진단:', JSON.stringify({path:apiPath, state, responses:responses.slice(start).map(r => ({status:r.status, expectedPath:new URL(r.url).pathname===target}))}));
      throw error;
    });
    assert.equal(response.status, expectedStatus);
    const payload = await command('Network.getResponseBody', { requestId: response.id });
    return JSON.parse(payload.base64Encoded ? Buffer.from(payload.body, 'base64').toString('utf8') : payload.body);
  }
  const summaries = await execute('/api/members');
  assert.equal(summaries.members.length, 5);
  assert.equal(summaries.members[0].groups.length, 1);
  assert.equal(summaries.page.totalElements, 5);
  const detail = await execute('/api/member/{id}', 'tu3fgxd45k');
  assert.equal(detail.id, 'tu3fgxd45k');
  assert.equal(detail.groups.length, 1);
  assert.equal(detail.groups[0].joinedAt, "2026-08-15 13:00");
  assert.equal(detail.groups[0].permission, "Editor");
  assert.equal("permissionName" in detail.groups[0], false);
  await evaluate('document.getElementById("service-specification").value="1"; document.getElementById("service-specification").dispatchEvent(new Event("change"))');
  await waitFor(() => evaluate('window.ui.getConfigs().url === "/internal-docs/group" && !!window.ui.specSelectors.specJson().get("openapi")'));
  assert.deepEqual(await evaluate('window.ui.getConfigs().supportedSubmitMethods'), ['get']);
  assert.equal(await evaluate('window.ui.specSelectors.specJson().getIn(["servers",0,"url"])'), '/internal-api/group');
  assert.equal(await evaluate('!!window.ui.specSelectors.specJson().get("security")'), false);
  assert.equal(await evaluate('window.ui.specSelectors.specJson().getIn(["info","title"])'), '그룹 서비스 API');
  assert.deepEqual(await evaluate('Object.keys(window.ui.specSelectors.specJson().toJS().paths).sort()'),
    ['/api/group/{memberId}', '/api/groups/summary']);
  assert.equal(await evaluate(`(() => {
    try { window.ui.getConfigs().requestInterceptor({url: '/api/members', method: 'GET'}); return false; }
    catch { return true; }
  })()`), true);
  const groupSummary = await execute('/api/groups/summary', 'tu3fgxd45k', 'memberIds', '/internal-api/group');
  assert.equal(groupSummary.group[0].totalMemberCount, 2);
  assert.deepEqual(groupSummary.group[0].members, [{memberId:'tu3fgxd45k', permission:'Editor'}]);
  const groupDetail = await execute('/api/group/{memberId}', 'tu3fgxd45k', 'memberId', '/internal-api/group');
  assert.deepEqual(Object.keys(groupDetail).sort(), ['groupId','groupName','memberId','permission','pricing','registeredDate']);
  assert.equal(groupDetail.registeredDate, '2026-08-15 13:00');
  assert.equal(groupDetail.permission, 'Editor');
  await evaluate('document.getElementById("service-specification").value="0"; document.getElementById("service-specification").dispatchEvent(new Event("change"))');
  await waitFor(() => evaluate('window.ui.getConfigs().url === "/v3/api-docs" && !!window.ui.specSelectors.specJson().get("openapi")'));
  assert.equal(await evaluate('window.ui.getConfigs().supportedSubmitMethods.includes("get")'), true);
  assert.equal(requests.some(url => url.startsWith(origin + '/internal-docs/group')), true);
  assert.equal(requests.some(url => /^https?:/.test(url) && new URL(url).origin !== origin), false);
  assert.equal(internalHeaderSent, false);
  console.log('통과: 실제 Edge Swagger 멤버·그룹 목록과 상세 Try it out 200·단일 소속·멤버 복귀');
  console.log('통과: 테스트 명세 주입 없음·외부 직접 요청 없음·브라우저 내부 키 헤더 없음');
  console.log('범위: 루트 Compose의 멤버 → 그룹 실제 HTTP 업무 연동');
  await command('Browser.close').catch(() => {});
} finally {
  socket?.close();
  if (browser.exitCode === null) browser.kill();
}
