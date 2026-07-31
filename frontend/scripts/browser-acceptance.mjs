import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';

const webUrl = (process.env.SEMEVOSQL_WEB_URL || 'http://127.0.0.1:23000').replace(/\/$/, '');
const projectId = process.env.SEMEVOSQL_ACCEPTANCE_PROJECT_ID?.trim();

const browserCandidates = [
  process.env.SEMEVOSQL_BROWSER_BIN,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
  join(homedir(), 'Applications/Google Chrome.app/Contents/MacOS/Google Chrome'),
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  process.env.PROGRAMFILES
    ? join(process.env.PROGRAMFILES, 'Google/Chrome/Application/chrome.exe')
    : undefined,
  process.env['PROGRAMFILES(X86)']
    ? join(process.env['PROGRAMFILES(X86)'], 'Google/Chrome/Application/chrome.exe')
    : undefined,
].filter(Boolean);

const browser = browserCandidates.find((candidate) => existsSync(candidate));
if (!browser) {
  throw new Error(
    'No Chrome/Chromium executable found. Set SEMEVOSQL_BROWSER_BIN to a local browser executable.',
  );
}

const cases = [
  {
    name: 'project list',
    path: '/projects',
    includes: ['项目工作台', '项目总数'],
    excludes: ['项目列表加载失败'],
  },
  {
    name: 'model configuration',
    path: '/admin/models',
    includes: ['模型服务', '新增配置', '筛选模型类型'],
    excludes: [],
  },
];

if (projectId) {
  cases.push(
    {
      name: 'project overview',
      path: `/projects/${encodeURIComponent(projectId)}`,
      includes: ['项目概览', '验证与发布'],
      excludes: ['项目详情加载失败'],
    },
    {
      name: 'semantic governance',
      path: `/projects/${encodeURIComponent(projectId)}?section=release`,
      includes: ['业务模型版本与资料更新', '业务模型版本', '资料修订', '变更记录'],
      excludes: ['项目详情加载失败'],
    },
    {
      name: 'external agent integration',
      path: `/projects/${encodeURIComponent(projectId)}?section=external`,
      includes: ['外部智能助手接入'],
      excludes: [],
    },
    {
      name: 'semantic improvement',
      path: `/projects/${encodeURIComponent(projectId)}?section=evolution`,
      includes: ['业务模型建议'],
      excludes: [],
    },
    {
      name: 'project selection before chat',
      path: '/chat',
      includes: ['先选择项目，再开始查询'],
      excludes: ['问一个具体问题'],
    },
    {
      name: 'project chat',
      path: `/chat?projectId=${encodeURIComponent(projectId)}`,
      includes: ['发送'],
      attributes: [
        {
          selector: 'textarea[aria-label]',
          attribute: 'aria-label',
          includes: '要查询的业务问题',
        },
      ],
      excludes: [],
    },
  );
}

const profileDirectory = mkdtempSync(join(tmpdir(), 'semevosql-browser-'));
const chrome = spawn(
  browser,
  [
    '--headless=new',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-features=Translate,MediaRouter',
    `--user-data-dir=${profileDirectory}`,
    '--remote-debugging-port=0',
    'about:blank',
  ],
  { stdio: ['ignore', 'ignore', 'pipe'] },
);

chrome.stderr.setEncoding('utf8');
let chromeStderr = '';
let devtoolsResolve;
let devtoolsReject;
const devtoolsUrl = new Promise((resolve, reject) => {
  devtoolsResolve = resolve;
  devtoolsReject = reject;
});

const startupTimer = setTimeout(() => {
  devtoolsReject(new Error(`Chrome DevTools did not start. ${chromeStderr.trim()}`));
}, 20_000);

chrome.stderr.on('data', (chunk) => {
  chromeStderr += chunk;
  const match = chromeStderr.match(/DevTools listening on (ws:\/\/[^\s]+)/);
  if (match) {
    clearTimeout(startupTimer);
    devtoolsResolve(match[1]);
  }
});
chrome.on('error', (error) => devtoolsReject(error));
chrome.on('exit', (code) => {
  if (code && code !== 0) {
    devtoolsReject(new Error(`Chrome exited before acceptance completed: ${code}. ${chromeStderr.trim()}`));
  }
});

let socket;
const pending = new Map();
const eventWaiters = [];
let messageId = 0;

const closeResources = () => {
  try {
    socket?.close();
  } catch {
    // Best-effort cleanup only.
  }
  if (!chrome.killed) chrome.kill('SIGTERM');
  try {
    rmSync(profileDirectory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  } catch (error) {
    console.warn(
      `[browser-acceptance] temporary Chrome profile cleanup skipped: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
};

try {
  const websocketUrl = await devtoolsUrl;
  socket = new WebSocket(websocketUrl);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out connecting to Chrome DevTools.')), 10_000);
    socket.addEventListener('open', () => {
      clearTimeout(timer);
      resolve();
    });
    socket.addEventListener('error', (event) => {
      clearTimeout(timer);
      reject(new Error(`Chrome DevTools WebSocket error: ${String(event)}`));
    });
  });

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(String(event.data));
    if (message.id && pending.has(message.id)) {
      const { resolve, reject, timer } = pending.get(message.id);
      pending.delete(message.id);
      clearTimeout(timer);
      if (message.error) reject(new Error(JSON.stringify(message.error)));
      else resolve(message.result || {});
      return;
    }

    if (!message.method) return;
    const index = eventWaiters.findIndex(
      (waiter) =>
        waiter.method === message.method &&
        (!waiter.sessionId || waiter.sessionId === message.sessionId),
    );
    if (index >= 0) {
      const [waiter] = eventWaiters.splice(index, 1);
      clearTimeout(waiter.timer);
      waiter.resolve(message.params || {});
    }
  });

  const command = (method, params = {}, sessionId, timeoutMs = 15_000) => {
    const id = ++messageId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Timed out waiting for CDP command ${method}.`));
      }, timeoutMs);
      pending.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  };

  const waitForEvent = (method, sessionId, timeoutMs = 15_000) =>
    new Promise((resolve, reject) => {
      const waiter = { method, sessionId, resolve, reject, timer: undefined };
      waiter.timer = setTimeout(() => {
        const index = eventWaiters.indexOf(waiter);
        if (index >= 0) eventWaiters.splice(index, 1);
        reject(new Error(`Timed out waiting for CDP event ${method}.`));
      }, timeoutMs);
      eventWaiters.push(waiter);
    });

  const { targetId } = await command('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await command('Target.attachToTarget', { targetId, flatten: true });
  await command('Page.enable', {}, sessionId);
  await command('Runtime.enable', {}, sessionId);

  for (const testCase of cases) {
    const url = `${webUrl}${testCase.path}`;
    const loaded = waitForEvent('Page.loadEventFired', sessionId);
    await command('Page.navigate', { url }, sessionId);
    await loaded;

    const readinessDeadline = Date.now() + 12_000;
    let bodyText = '';
    let missing = [...testCase.includes];
    let missingAttributes = [];
    let forbidden = [];
    do {
      const evaluation = await command(
        'Runtime.evaluate',
        {
          expression: "document.body?.innerText || ''",
          returnByValue: true,
        },
        sessionId,
      );
      bodyText = evaluation.result?.value || '';
      missing = testCase.includes.filter((text) => !bodyText.includes(text));
      missingAttributes = [];
      for (const rule of testCase.attributes || []) {
        const attribute = await command(
          'Runtime.evaluate',
          {
            expression: `document.querySelector(${JSON.stringify(rule.selector)})?.getAttribute(${JSON.stringify(rule.attribute)}) || ''`,
            returnByValue: true,
          },
          sessionId,
        );
        if (!String(attribute.result?.value || '').includes(rule.includes)) {
          missingAttributes.push(`${rule.attribute}=${rule.includes}`);
        }
      }
      forbidden = testCase.excludes.filter((text) => bodyText.includes(text));
      if (!missing.length && !missingAttributes.length && !forbidden.length) break;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    } while (Date.now() < readinessDeadline);

    if (missing.length || missingAttributes.length || forbidden.length) {
      throw new Error(
        [
          `Browser acceptance failed for ${testCase.name} (${url}).`,
          missing.length ? `Missing: ${missing.join(', ')}` : '',
          missingAttributes.length ? `Missing attributes: ${missingAttributes.join(', ')}` : '',
          forbidden.length ? `Unexpected: ${forbidden.join(', ')}` : '',
          bodyText ? `Body: ${bodyText.slice(0, 1200).replace(/\s+/g, ' ').trim()}` : '',
        ]
          .filter(Boolean)
          .join(' '),
      );
    }

    console.log(`[browser-acceptance] PASS ${testCase.name}: ${url}`);
  }

  await command('Target.closeTarget', { targetId });
  console.log(
    `[browser-acceptance] ${cases.length}/${cases.length} routes passed with ${browser}`,
  );
} finally {
  closeResources();
}
