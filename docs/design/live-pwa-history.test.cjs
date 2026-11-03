const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'live-pwa-history.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const start = script.indexOf('function createHistoryDemo(');
const end = script.indexOf('const historyDemo =');
assert.ok(start >= 0 && end > start);
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(script.slice(start, end), sandbox);
let count = 0;
function test(name, run) { run(); console.log('PASS ' + name); count++; }
function fixture() {
  const tasks = new Map();
  let id = 0;
  const scheduler = {
    setInterval(callback) { tasks.set(++id, callback); return id; },
    clearInterval(key) { tasks.delete(key); },
  };
  const changes = [];
  const demo = sandbox.createHistoryDemo(value => changes.push(value), scheduler);
  const item = {id:2, camera:'yard', start:51840, end:51892};
  return {demo, item, tasks, changes, tick() { [...tasks.values()].forEach(callback => callback()); }};
}

test('opening review seeks four seconds before activity, not a bounded clip', () => {
  const {demo, item} = fixture();
  demo.open(item);
  const value = demo.snapshot();
  assert.equal(value.timestamp, item.start - 4);
  assert.ok(value.rangeEnd > item.end + 4);
  assert.equal(value.rangeEnd - value.rangeStart, 86400);
  assert.equal(value.playing, true);
  assert.ok(value.rangeStart < item.start - 4);
});
test('review outside selected archive range falls back to its last minute', () => {
  for (const start of [-100000, 100000]) {
    const {demo, item} = fixture();
    demo.open({...item, start});
    assert.equal(demo.snapshot().timestamp, demo.snapshot().rangeEnd - 60);
    assert.equal(demo.snapshot().playing, true);
  }
});
test('camera switch preserves wall-clock timestamp', () => {
  const {demo, item} = fixture();
  demo.open(item);
  demo.seek(item.start + 20);
  demo.setCamera('driveway');
  assert.equal(demo.snapshot().camera, 'driveway');
  assert.equal(demo.snapshot().timestamp, item.start + 20);
});
test('timeline seek is not clamped to selected event', () => {
  const {demo, item} = fixture();
  demo.open(item);
  demo.seek(item.start - 120);
  assert.equal(demo.snapshot().timestamp, item.start - 120);
  demo.seek(item.end + 120);
  assert.equal(demo.snapshot().timestamp, item.end + 120);
});
test('invalid and out-of-range seek cannot corrupt time', () => {
  const {demo, item} = fixture();
  demo.open(item);
  const before = demo.snapshot().timestamp;
  demo.seek(NaN);
  assert.equal(demo.snapshot().timestamp, before);
  demo.seek(-Infinity);
  assert.equal(demo.snapshot().timestamp, before);
  demo.seek(demo.snapshot().rangeStart - 1);
  assert.equal(demo.snapshot().timestamp, demo.snapshot().rangeStart);
  demo.seek(1000000);
  assert.equal(demo.snapshot().timestamp, demo.snapshot().rangeEnd);
});
test('playback speed advances archive time without creating duplicate timers', () => {
  const {demo, item, tasks, tick} = fixture();
  demo.open(item);
  demo.pause();
  demo.setRate(4);
  demo.toggle();
  const before = demo.snapshot().timestamp;
  tick();
  assert.equal(demo.snapshot().timestamp, before + 4);
  assert.equal(tasks.size, 1);
  demo.setCamera('entrance');
  assert.equal(tasks.size, 1);
});
test('pausing and closing release simulated playback', () => {
  const {demo, item, tasks} = fixture();
  demo.open(item);
  demo.pause();
  assert.equal(tasks.size, 0);
  demo.toggle();
  assert.equal(tasks.size, 1);
  demo.close();
  assert.equal(tasks.size, 0);
  assert.equal(demo.snapshot().playing, false);
});
test('end of selected event does not stop full History playback', () => {
  const {demo, item, tick} = fixture();
  demo.open(item);
  demo.pause();
  demo.seek(item.end);
  demo.toggle();
  tick();
  assert.equal(demo.snapshot().timestamp, item.end + 1);
  assert.equal(demo.snapshot().playing, true);
});
test('archive end stops playback and does not silently become Live', () => {
  const {demo, item, tasks, tick} = fixture();
  demo.open(item);
  demo.pause();
  demo.seek(demo.snapshot().rangeEnd - 1);
  demo.toggle();
  tick();
  assert.equal(demo.snapshot().playing, false);
  assert.equal(tasks.size, 0);
  assert.equal(demo.snapshot().active, true);
});
test('new review replaces seek target instead of inheriting previous position', () => {
  const {demo, item} = fixture();
  demo.open(item);
  demo.seek(item.start + 90);
  demo.open({...item, id:5, camera:'entrance', start:51000, end:51040});
  assert.equal(demo.snapshot().timestamp, 50996);
  assert.equal(demo.snapshot().camera, 'entrance');
});
test('snapshot mutation cannot modify controller state', () => {
  const {demo, item} = fixture();
  demo.open(item);
  const value = demo.snapshot();
  value.timestamp = -3;
  assert.equal(demo.snapshot().timestamp, item.start - 4);
});
test('viewer has PWA panel modes, timeline, camera/date and native navigation targets', () => {
  for (const marker of ['id="history-view"', 'id="history-timeline"', 'data-panel="timeline"', 'data-panel="events"', 'data-panel="detail"', 'id="history-camera"', 'id="history-date"', 'id="history-back"', 'id="history-live"']) {
    assert.ok(html.includes(marker), marker);
  }
  assert.equal(html.includes('id="seek"'), false, 'Do not replace archive timeline with the rejected clip slider');
});
test('standalone mockup does not call Frigate or fetch private assets', () => {
  assert.equal(/\b(?:fetch|XMLHttpRequest|WebSocket)\s*\(/.test(script), false);
  assert.equal(/\b(?:src|href)=["'](?:https?:|\/\/)/i.test(html), false);
  assert.ok(html.includes('синтетические'));
});
console.log(`${count} History demo checks passed; not browser rendering or PWA runtime verification`);
