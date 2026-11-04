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
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Network.requestWillBeSent') {
      // 값이나 헤더는 보관하지 않는다.
      requests.push(message.params.request.url);
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
  // 업무 API가 아직 없어 UI 실행 버튼 검증에만 메모리상의 테스트 명세를 넣는다.
  const insertProbe = `(() => {
    const spec = window.ui.specSelectors.specJson().toJS();
    spec.paths = { '/verification-only': {get: {summary: '화면 검사 전용', responses: {'200': {description: '검사'}}}}};
    window.ui.specActions.updateSpec(JSON.stringify(spec));
    return true;
  })()`;
  await evaluate(insertProbe);
  await waitFor(() => evaluate('!!document.querySelector(".opblock-summary")'));
  await evaluate('document.querySelector(".opblock-summary-control").click()');
  await waitFor(() => evaluate('!!document.querySelector(".try-out__btn")'));
  await evaluate('document.getElementById("service-specification").value="1"; document.getElementById("service-specification").dispatchEvent(new Event("change"))');
  await waitFor(() => evaluate('window.ui.getConfigs().url === "/internal-docs/group" && !!window.ui.specSelectors.specJson().get("openapi")'));
  assert.equal(await evaluate('window.ui.getConfigs().supportedSubmitMethods.length'), 0);
  assert.equal(await evaluate('!!window.ui.specSelectors.specJson().get("security")'), false);
  await evaluate(insertProbe);
  await waitFor(() => evaluate('!!document.querySelector(".opblock-summary")'));
  await evaluate('document.querySelector(".opblock-summary-control").click()');
  await sleep(300);
  assert.equal(await evaluate('!!document.querySelector(".try-out__btn")'), false);
  await evaluate('document.getElementById("service-specification").value="0"; document.getElementById("service-specification").dispatchEvent(new Event("change"))');
  await waitFor(() => evaluate('window.ui.getConfigs().url === "/v3/api-docs" && !!window.ui.specSelectors.specJson().get("openapi")'));
  assert.equal(await evaluate('window.ui.getConfigs().supportedSubmitMethods.includes("get")'), true);
  assert.equal(requests.some(url => url.startsWith(origin + '/internal-docs/group')), true);
  assert.equal(requests.some(url => /^https?:/.test(url) && new URL(url).origin !== origin), false);
  console.log('통과: 실제 Edge 명세 조회·선택·멤버 실행 버튼·그룹 읽기 전용·멤버로 복귀·외부 직접 요청 없음');
  console.log('범위: 실행 버튼 검사는 브라우저 메모리의 테스트 명세로 확인, 업무 API 호출은 수행하지 않음');
  await command('Browser.close').catch(() => {});
} finally {
  socket?.close();
  if (browser.exitCode === null) browser.kill();
}
