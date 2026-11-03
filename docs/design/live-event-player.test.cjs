const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'live-event-player.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const controllerSource = script.slice(script.indexOf('function createDemoPlayback('), script.indexOf('const screenPicker ='));
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(controllerSource, sandbox);
let count = 0;
function test(name, body) {
  body();
  count++;
  console.log('PASS ' + name);
}
function fixture(mode = 'ready') {
  const tasks = new Map();
  let nextId = 0;
  let updates = 0;
  const scheduler = {
    setInterval(callback) { const id = ++nextId; tasks.set(id, callback); return id; },
    clearInterval(id) { tasks.delete(id); },
  };
  const player = sandbox.createDemoPlayback(() => { updates++; }, scheduler);
  player.open(mode);
  return {player, tasks, tick() { for (const callback of [...tasks.values()]) callback(); }, updates: () => updates};
}

test('JavaScript parses and original carousel renderer is preserved', () => {
  const original = fs.readFileSync(path.join(__dirname, 'live-unreviewed-carousel.html'), 'utf8');
  const oldRenderer = original.match(/function render\(\)\{[^\n]+/)[0];
  const newRenderer = script.match(/function renderReviewCards\(\)\{[^\n]+/)[0].replace('renderReviewCards()', 'render()');
  assert.equal(newRenderer, oldRenderer);
});
test('starts paused at a sample position without a timer', () => {
  const {player, tasks} = fixture();
  assert.equal(player.snapshot().position, 8);
  assert.equal(player.snapshot().duration, 52);
  assert.equal(player.snapshot().playing, false);
  assert.equal(tasks.size, 0);
});
test('play advances one second and pause cancels timer', () => {
  const {player, tasks, tick} = fixture();
  player.toggle();
  assert.equal(tasks.size, 1);
  tick();
  assert.equal(player.snapshot().position, 9);
  player.toggle();
  assert.equal(tasks.size, 0);
  tick();
  assert.equal(player.snapshot().position, 9);
});
test('seek clamps negative, excessive and invalid positions', () => {
  const {player} = fixture();
  player.seek(-10);
  assert.equal(player.snapshot().position, 0);
  player.seek(100);
  assert.equal(player.snapshot().position, 52);
  player.seek(NaN);
  assert.equal(player.snapshot().position, 0);
});
test('seeking during playback keeps only the existing timer', () => {
  const {player, tasks, tick} = fixture();
  player.toggle();
  player.seek(24);
  assert.equal(tasks.size, 1);
  tick();
  assert.equal(player.snapshot().position, 25);
});
test('reaching end stops and replay starts at zero', () => {
  const {player, tasks, tick} = fixture();
  player.seek(51);
  player.toggle();
  tick();
  assert.equal(player.snapshot().position, 52);
  assert.equal(player.snapshot().playing, false);
  assert.equal(tasks.size, 0);
  player.toggle();
  assert.equal(player.snapshot().position, 0);
  assert.equal(tasks.size, 1);
});
test('seeking to end cancels an active timer', () => {
  const {player, tasks} = fixture();
  player.toggle();
  player.seek(52);
  assert.equal(player.snapshot().playing, false);
  assert.equal(tasks.size, 0);
});
for (const mode of ['buffering', 'missing', 'error']) {
  test(mode + ' disables playback and cancels old work', () => {
    const {player, tasks} = fixture();
    player.toggle();
    player.setMode(mode);
    assert.equal(tasks.size, 0);
    assert.equal(player.snapshot().playing, false);
    player.toggle();
    player.seek(30);
    assert.equal(player.snapshot().position, 8);
    assert.equal(tasks.size, 0);
    player.setMode('ready');
    player.toggle();
    assert.equal(tasks.size, 1);
  });
}
test('ongoing refresh extends only available fragment', () => {
  const {player} = fixture('ongoing');
  assert.equal(player.snapshot().duration, 38);
  player.extend();
  assert.equal(player.snapshot().duration, 53);
  assert.equal(player.snapshot().position, 8);
  assert.equal(player.snapshot().mode, 'ongoing');
});
test('completed fragment does not grow on refresh', () => {
  const {player} = fixture();
  player.extend();
  assert.equal(player.snapshot().duration, 52);
});
test('closing cancels timer and reopening resets per-event position', () => {
  const {player, tasks, tick} = fixture();
  player.seek(30);
  player.toggle();
  player.close();
  assert.equal(tasks.size, 0);
  tick();
  assert.equal(player.snapshot().position, 30);
  player.open('ongoing');
  assert.equal(player.snapshot().position, 8);
  assert.equal(player.snapshot().duration, 38);
  assert.equal(player.snapshot().playing, false);
});
test('snapshot is not mutable controller state', () => {
  const {player} = fixture();
  const state = player.snapshot();
  state.duration = -10;
  assert.equal(player.snapshot().duration, 52);
});
test('elapsed time and wall clock formatting', () => {
  assert.equal(sandbox.formatElapsed(0), '00:00');
  assert.equal(sandbox.formatElapsed(65), '01:05');
  assert.equal(sandbox.formatClock(14 * 3600 + 24 * 60 + 8), '14:24:08');
  assert.equal(sandbox.formatClock(86401), '00:00:01');
  assert.equal(sandbox.formatClock(-1), '23:59:59');
});
test('prototype has no remote media or API calls', () => {
  assert.equal(/\b(?:src|href)=["'](?:https?:|\/\/)/i.test(html), false);
  assert.equal(/\b(?:fetch|XMLHttpRequest|WebSocket)\s*\(/.test(script), false);
  assert.ok(html.includes('прогресс моделируется таймером'));
  assert.ok(html.includes('aria-label="Позиция в записи"'));
  assert.ok(html.includes('[hidden]{display:none!important}'));
});
console.log(`${count} prototype checks passed (not a browser rendering test)`);
