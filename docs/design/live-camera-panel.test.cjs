const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'live-camera-panel.html'), 'utf8');
const css = html.slice(html.indexOf('<style>'), html.indexOf('</style>'));
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const start = script.indexOf('/* PURE ZOOM MODEL START */');
const end = script.indexOf('/* PURE ZOOM MODEL END */');
assert.ok(start >= 0 && end > start);
const sandbox = {};
vm.createContext(sandbox);
vm.runInContext(script.slice(start, end), sandbox);
let count = 0;
function test(name, run) { run(); console.log('PASS ' + name); count++; }

// The content point under a screen position, in the pixels of the source picture.
function contentAt(zoom, width, height, x, y) {
  const centerX = width / 2, centerY = height / 2;
  return {
    x: centerX + (x - centerX - zoom.offsetX) / zoom.scale,
    y: centerY + (y - centerY - zoom.offsetY) / zoom.scale,
  };
}

// The mockup counts the limit in the physical pixels of the phone; the geometry is in CSS pixels.
// The picture of the panel is 344 dp wide: 344 x 3 = 1032 physical pixels of the screen.
const WIDTH = 360, HEIGHT = 202, PICTURE_WIDTH_PX = 1032;
// A neutral viewport for the pure model: the numbers come out round.
const GENERIC_VIEWPORT_PX = 960;
function fixture(sourceWidthPx) {
  const model = sandbox.createZoomModel({sourceWidthPx});
  model.setViewport(WIDTH, HEIGHT, PICTURE_WIDTH_PX);
  return model;
}

test('the zoom limit follows the resolution of the stream', () => {
  assert.equal(sandbox.zoomMaxScale(2560, GENERIC_VIEWPORT_PX), 8);
  assert.equal(sandbox.zoomMaxScale(1920, GENERIC_VIEWPORT_PX), 6);
  assert.equal(sandbox.zoomMaxScale(1280, GENERIC_VIEWPORT_PX), 4);
  assert.equal(sandbox.zoomMaxScale(640, GENERIC_VIEWPORT_PX), 2);
  assert.equal(sandbox.zoomMaxScale(3840, GENERIC_VIEWPORT_PX), 10, 'the 4K stream reaches the ceiling');
});

test('an unknown stream or viewport falls back to 4x', () => {
  assert.equal(sandbox.zoomMaxScale(0, PICTURE_WIDTH_PX), 4);
  assert.equal(sandbox.zoomMaxScale(3840, 0), 4);
  assert.equal(fixture(0).snapshot().maxScale, 4);
});

test('the limit never exceeds ten times', () => {
  assert.equal(sandbox.zoomMaxScale(12000, PICTURE_WIDTH_PX), 10);
});

test('the zoom cannot pass the limit of the stream', () => {
  const model = fixture(3840);
  model.zoomAroundPoint(100, WIDTH / 2, HEIGHT / 2);
  assert.equal(model.snapshot().scale, 10);
});

test('the point under the pinch stays under the finger', () => {
  const model = fixture(3840);
  const focus = {x: 90, y: 50};
  const before = contentAt(model.snapshot(), WIDTH, HEIGHT, focus.x, focus.y);
  model.zoomAroundPoint(2, focus.x, focus.y);
  const after = contentAt(model.snapshot(), WIDTH, HEIGHT, focus.x, focus.y);
  assert.ok(Math.abs(before.x - after.x) < 1e-3, before.x + ' vs ' + after.x);
  assert.ok(Math.abs(before.y - after.y) < 1e-3, before.y + ' vs ' + after.y);
});

test('the picture never leaves the frame', () => {
  const model = fixture(3840);
  model.zoomAroundPoint(8, 0, 0);
  const value = model.snapshot();
  assert.ok(Math.abs(value.offsetX) <= (value.scale - 1) * WIDTH / 2 + 1e-6);
  assert.ok(Math.abs(value.offsetY) <= (value.scale - 1) * HEIGHT / 2 + 1e-6);
});

test('a drag at 1x moves nothing', () => {
  const model = fixture(3840);
  model.pan(120, 80);
  const value = model.snapshot();
  assert.equal(value.scale, 1);
  assert.equal(value.offsetX, 0);
  assert.equal(value.offsetY, 0);
});

test('a drag of a zoomed picture is bounded by the frame', () => {
  const model = fixture(3840);
  model.zoomAroundPoint(2, WIDTH / 2, HEIGHT / 2);
  model.pan(10000, 10000);
  const value = model.snapshot();
  assert.ok(value.offsetX <= (value.scale - 1) * WIDTH / 2 + 1e-6);
  assert.ok(value.offsetY <= (value.scale - 1) * HEIGHT / 2 + 1e-6);
});

test('the double tap returns the picture to the whole frame', () => {
  const model = fixture(3840);
  model.zoomAroundPoint(3, 100, 60);
  model.pan(40, 20);
  model.reset();
  const value = model.snapshot();
  assert.equal(value.scale, 1);
  assert.equal(value.offsetX, 0);
  assert.equal(value.offsetY, 0);
});

test('the mockup shows the camera panel and the archive with the same zoom', () => {
  for (const marker of ['id="live-screen"', 'id="live-picture"', 'id="live-stage"', 'id="live-objects"', 'id="live-motion"', 'id="live-name"', 'id="live-state"', 'id="live-error"', 'id="archive-screen"', 'id="archive-picture"', 'id="archive-controls"', 'id="archive-stage"']) {
    assert.ok(html.includes(marker), marker);
  }
  assert.equal(html.includes('id="seek"'), false, 'Do not replace the archive timeline with a clip slider');
});

test('the narrow header of the PWA: back, history, fullscreen and the cog', () => {
  const header = html.slice(html.indexOf('<header class="camera-header"'), html.indexOf('class="stage"'));
  for (const id of ['back', 'history', 'fullscreen', 'features']) {
    assert.ok(header.includes(`id="${id}"`), id + ' is in the header');
  }
  // The manual record and the snapshot of the frame belong to the wide layout.
  assert.ok(header.includes('class="header-button is-off wide-only" id="event"'));
  assert.ok(header.includes('class="header-button wide-only" id="shot"'));
});

test('the wide header stays short: the camera toggles moved into the menu of the cog', () => {
  const header = html.slice(html.indexOf('<header class="camera-header"'), html.indexOf('class="stage"'));
  assert.ok(html.includes('<span class="header-label wide-only">Назад</span>'));
  assert.ok(html.includes('<span class="header-label wide-only">История</span>'));
  for (const id of ['toggle-power', 'toggle-detect', 'toggle-record', 'toggle-snapshots',
                    'toggle-audio', 'toggle-transcription', 'toggle-autotracking']) {
    assert.equal(header.includes(`id="${id}"`), false, id + ' is not a button of the header');
  }
  // The buttons of the header that the user sees at once (the row of the fullscreen is hidden).
  const buttons = header.match(/<button(?![^>]*fs-only)/g) || [];
  assert.ok(buttons.length <= 10, 'the header shows ' + buttons.length + ' buttons, not a wall of them');
  assert.ok(html.includes('.live-screen.wide-layout .wide-only{display:inline-grid}'),
    'the wide layout shows what the narrow one hides');
});

test('the narrow screen opens the sheet of the camera settings', () => {
  const drawer = html.slice(html.indexOf('id="camera-drawer"'), html.indexOf('id="features-menu"'));
  for (const label of ['Камера', 'Детекция объектов', 'Запись', 'Снимки', 'Аудиодетекция', 'Транскрипция',
                       'Автотрекинг', 'Поток', 'Режим', 'Низкая полоса', 'Ручная запись',
                       'Воспроизведение в фоне', 'Статистика', 'Отладочный вид']) {
    assert.ok(drawer.includes(label), label + ' is in the sheet');
  }
  assert.ok(drawer.includes('id="sheet-shot"'), 'the snapshot of the frame is in the sheet');
  assert.ok(drawer.includes('id="sheet-event"'), 'the manual record is in the sheet');
  assert.ok(html.includes('.sheet{position:absolute;left:0;right:0;bottom:0'), 'the sheet comes from the bottom');
});

test('the wide screen opens the dropdown menu with the settings of the camera', () => {
  const menu = html.slice(html.indexOf('id="features-menu"'), html.indexOf('id="live-toast"'));
  // Nothing of the settings of the PWA is lost in the wide layout.
  for (const label of ['Камера', 'Детекция объектов', 'Запись', 'Снимки', 'Аудиодетекция', 'Транскрипция',
                       'Автотрекинг', 'Поток', 'Режим', 'Низкая полоса', 'Ручная запись',
                       'Воспроизведение в фоне', 'Статистика', 'Отладочный вид']) {
    assert.ok(menu.includes(label), label + ' is in the menu');
  }
  assert.ok(menu.includes('id="menu-shot"'), 'the snapshot of the frame is in the menu');
  assert.ok(html.includes('.dropdown{position:absolute'), 'the menu drops from the cog');
});

test('the PTZ panel of the PWA is dimmed: the app has no PTZ', () => {
  const ptz = html.slice(html.indexOf('id="live-ptz"'), html.indexOf('id="sheet-scrim"'));
  for (const id of ['ptz-left', 'ptz-up', 'ptz-down', 'ptz-right', 'ptz-zoom-in', 'ptz-zoom-out', 'ptz-presets']) {
    assert.ok(ptz.includes(`id="${id}"`), id + ' is in the PTZ panel');
  }
  assert.ok(html.includes('.ptz{position:absolute;left:0;right:0;bottom:10%'), 'the panel sits under the frame');
  assert.ok(/\.ptz\{[^}]*opacity:\.45\}/.test(html), 'the panel is dimmed');
});

test('the features icon is a gear and not a sun', () => {
  assert.ok(html.includes('M12.22 2h-.44'), 'the outline of a gear');
  assert.equal(html.includes('M12 2.5V5'), false, 'the rays of the sun are gone');
});

test('the fullscreen turns the header into a floating row on the right', () => {
  assert.ok(html.includes('.live-screen.fullscreen .camera-header{position:absolute;right:8px;top:4px'));
  assert.ok(html.includes('.live-screen.fullscreen #header-left{display:none}'), 'the left group leaves the screen');
  assert.ok(html.includes('.live-screen.fullscreen #features{display:none}'), 'the cog hides in the fullscreen');
  assert.ok(html.includes('class="header-button filled fs-only" id="fs-back"'), 'the filled back button');
});

test('the archive controls stay outside the transformed picture', () => {
  const stage = html.slice(html.indexOf('id="archive-stage"'), html.indexOf('class="archive-timeline"'));
  const pictureAt = stage.indexOf('id="archive-picture"');
  const controlsAt = stage.indexOf('id="archive-controls"');
  assert.ok(pictureAt >= 0 && controlsAt > pictureAt, 'controls are drawn above the picture');
  assert.ok(stage.includes('archive-controls'), 'the archive keeps its floating controls');
});

test('the limit of the mockup is counted in the physical pixels of the screen', () => {
  assert.ok(script.includes('DEVICE_PIXEL_RATIO'));
  assert.ok(html.includes('360 dp × 3 = 1080 px'), 'the notice states the pixel ratio of the phone');
  assert.ok(script.includes('const DEVICE_PIXEL_RATIO = 1080 / 360;'), 'the mockup uses the width of the phone');
});

test('the limit on the phone of the owner: 4K reaches the ceiling, 2K stays below it', () => {
  // The picture of the panel is 344 dp wide: 1032 physical pixels of the screen.
  const screen = 1032;
  assert.equal(sandbox.zoomMaxScale(3840, screen), 10, 'the ceiling of ten times');
  assert.ok(Math.abs(sandbox.zoomMaxScale(2560, screen) - 7.44) < 0.01);
  assert.ok(Math.abs(sandbox.zoomMaxScale(1920, screen) - 5.58) < 0.01);
});

test('the standalone mockup does not call Frigate or fetch private assets', () => {
  assert.equal(/\b(?:fetch|XMLHttpRequest|WebSocket)\s*\(/.test(script), false);
  assert.equal(/\b(?:src|href)=["'](?:https?:|\/\/)/i.test(html), false);
  assert.ok(html.includes('синтетические'));
});

/* --- A DOM double: the whole script runs against it, so a broken page cannot reach the user. --- */
function createElement(id) {
  const handlers = {};
  const classes = new Set();
  return {
    id,
    innerHTML: '',
    textContent: '',
    hidden: false,
    value: '',
    style: {},
    attributes: {},
    classes,
    handlers,
    clientWidth: 360,
    clientHeight: 702,
    classList: {
      toggle(name, force) {
        const on = force === undefined ? !classes.has(name) : force;
        if (on) classes.add(name); else classes.delete(name);
        return on;
      },
      add(name) { classes.add(name); },
      remove(name) { classes.delete(name); },
      contains(name) { return classes.has(name); },
    },
    addEventListener(type, handler) { (handlers[type] = handlers[type] || []).push(handler); },
    setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name] === undefined ? null : this.attributes[name]; },
    getBoundingClientRect() {
      return {
        left: 0,
        top: 0,
        width: Number.parseFloat(this.style.width) || 360,
        height: Number.parseFloat(this.style.height) || 202,
      };
    },
    setPointerCapture() {},
    dispatch(type, event) { for (const handler of this.handlers[type] || []) handler(event); },
  };
}

function createDom() {
  const registry = {};
  for (const match of html.matchAll(/id="([^"]+)"/g)) registry[match[1]] = createElement(match[1]);
  // The markup starts with some elements hidden and some dimmed: the double keeps that state.
  for (const tag of html.match(/<[a-z]+[^>]*>/g) || []) {
    const id = tag.match(/id="([^"]+)"/);
    if (!id) continue;
    if (/\bhidden\b/.test(tag)) registry[id[1]].hidden = true;
    const names = tag.match(/class="([^"]*)"/);
    if (names) names[1].split(/\s+/).filter(Boolean).forEach(name => registry[id[1]].classList.add(name));
  }
  const device = createElement('device');
  registry['screen'].value = 'live';
  registry['camera'].value = 'gate';
  registry['mode'].value = 'live';
  const document = {
    querySelector(selector) {
      if (selector.startsWith('#')) return registry[selector.slice(1)] || null;
      if (selector === '.device') return device;
      return null;
    },
    querySelectorAll() { return []; },
    addEventListener() {},
  };
  const window = {addEventListener() {}};
  const timers = [];
  const setTimeout = (fn) => { timers.push(fn); return timers.length; };
  return {document, window, registry, setTimeout, device};
}

/** The change of a select reaches the mockup as an event of the element itself. */
function change(element, value) {
  element.value = value;
  element.onchange({target: element});
}

function pointerEvent(x, y, pointerId, timeStamp) {
  return {
    pointerId, clientX: x, clientY: y, timeStamp,
    preventDefault() {},
  };
}

/** Two fingers spread apart around a centre: the gesture of the picture. */
function pinch(picture, center, timeStamp) {
  picture.dispatch('pointerdown', pointerEvent(center.x - 40, center.y, 1, timeStamp));
  picture.dispatch('pointerdown', pointerEvent(center.x + 40, center.y, 2, timeStamp));
  picture.dispatch('pointermove', pointerEvent(center.x - 80, center.y, 1, timeStamp + 10));
  picture.dispatch('pointermove', pointerEvent(center.x + 80, center.y, 2, timeStamp + 10));
  picture.dispatch('pointerup', pointerEvent(center.x + 80, center.y, 2, timeStamp + 20));
  picture.dispatch('pointerup', pointerEvent(center.x - 80, center.y, 1, timeStamp + 20));
}

test('the hidden attribute wins over the display of the mockup', () => {
  // Without this rule the author styles (display:flex/grid) beat the user agent rule of the
  // hidden attribute: both screens would be drawn at once and the offline card would sit on the
  // frame of a live camera.
  assert.ok(/\[hidden\]\{display:none!important\}/.test(html), 'the hidden attribute must hide the element');
  const ids = [...script.matchAll(/querySelector\('#([\w-]+)'\)/g)].map(match => match[1]);
  assert.ok(ids.length >= 10, 'the mockup addresses its elements by id');
  for (const id of new Set(ids)) {
    assert.ok(html.includes(`id="${id}"`), id + ' exists in the markup');
  }
});

test('the whole script runs against a DOM double without failing', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  assert.ok(dom.registry['live-picture'].style.width, 'the picture got its size');
  assert.ok(dom.registry['live-scene'].innerHTML.includes('<svg'), 'the synthetic frame is drawn');
  assert.equal(dom.registry['zoom-value'].textContent, '1.0×');
  assert.ok(dom.registry['zoom-limit'].textContent.endsWith('×'));
});

test('the camera panel shows the overlays of the PWA: object chip, motion dot, name only when offline', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  const registry = dom.registry;
  change(registry['mode'], 'live');
  assert.equal(registry['live-objects'].hidden, true);
  assert.equal(registry['live-motion'].hidden, true);
  assert.equal(registry['live-name'].hidden, true, 'the PWA hides the name of an online camera');
  change(registry['mode'], 'motion');
  assert.equal(registry['live-objects'].hidden, false);
  assert.equal(registry['live-motion'].hidden, false);
  change(registry['mode'], 'offline');
  assert.equal(registry['live-name'].hidden, false, 'the PWA shows the name of an offline camera');
  assert.equal(registry['live-state'].hidden, false);
  change(registry['mode'], 'tracking');
  assert.ok(registry['live-picture'].classes.has('tracking'));
  change(registry['mode'], 'failed');
  assert.equal(registry['live-error'].hidden, false);
});

test('a pinch on the frame enlarges the picture and a double tap returns it', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  const picture = dom.registry['live-picture'];
  pinch(picture, {x: 180, y: 100}, 1000);
  assert.ok(Number.parseFloat(dom.registry['zoom-value'].textContent) > 1, 'the pinch enlarged the frame');
  picture.dispatch('pointerdown', pointerEvent(100, 100, 5, 2000));
  picture.dispatch('pointerup', pointerEvent(100, 100, 5, 2010));
  picture.dispatch('pointerdown', pointerEvent(100, 100, 6, 2200));
  picture.dispatch('pointerup', pointerEvent(100, 100, 6, 2210));
  assert.equal(dom.registry['zoom-value'].textContent, '1.0×', 'the double tap returned the frame');
});

test('a snapshot says that the frame is saved', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  assert.equal(dom.registry['live-toast'].hidden, true);
  dom.registry['sheet-shot'].onclick();
  assert.equal(dom.registry['live-toast'].hidden, false);
  assert.equal(dom.registry['live-toast'].textContent, 'Кадр сохранён');
});

test('the cog opens the sheet on a narrow screen and the menu on a wide one', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  assert.equal(dom.registry['camera-drawer'].hidden, true);
  dom.registry['features'].onclick();
  assert.equal(dom.registry['camera-drawer'].hidden, false, 'the narrow screen opens the sheet');
  assert.equal(dom.registry['features-menu'].hidden, true);
  assert.equal(dom.registry['sheet-scrim'].hidden, false);
  dom.registry['sheet-scrim'].onclick();
  assert.equal(dom.registry['camera-drawer'].hidden, true);

  dom.registry['width'].onclick();
  assert.ok(dom.registry['live-screen'].classes.has('wide-layout'), 'the wide layout is on');
  dom.registry['features'].onclick();
  assert.equal(dom.registry['features-menu'].hidden, false, 'the wide screen opens the menu');
  assert.equal(dom.registry['camera-drawer'].hidden, true, 'the sheet of the narrow screen is closed');
});

test('the fullscreen moves the header into the corner of the screen', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  dom.registry['fullscreen'].onclick();
  assert.ok(dom.registry['live-screen'].classes.has('fullscreen'));
  dom.registry['fs-fullscreen'].onclick();
  assert.equal(dom.registry['live-screen'].classes.has('fullscreen'), false);
});

test('the archive reuses the same zoom and keeps its controls above the picture', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  change(dom.registry['screen'], 'archive');
  assert.equal(dom.registry['live-screen'].hidden, true);
  assert.equal(dom.registry['archive-screen'].hidden, false);
  const picture = dom.registry['archive-picture'];
  pinch(picture, {x: 180, y: 100}, 3000);
  assert.ok(Number.parseFloat(dom.registry['zoom-value'].textContent) > 1, 'the archive zooms too');
  assert.equal(dom.registry['archive-controls'].hidden, false, 'the controls stay');
  picture.dispatch('pointerdown', pointerEvent(100, 100, 7, 4000));
  picture.dispatch('pointerup', pointerEvent(100, 100, 7, 4010));
  assert.equal(dom.registry['archive-controls'].hidden, true, 'a tap on the frame hides the controls');
});

test('a swipe down closes the sheet of the settings', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  const drawer = dom.registry['camera-drawer'];
  const grab = dom.registry['sheet-grab'];
  dom.registry['features'].onclick();
  assert.equal(drawer.hidden, false, 'the sheet is open');
  grab.dispatch('pointerdown', pointerEvent(180, 600, 11, 1000));
  grab.dispatch('pointermove', pointerEvent(180, 700, 11, 1010));
  assert.equal(drawer.hidden, false, 'the sheet follows the finger');
  assert.ok(String(drawer.style.transform).includes('translateY'), 'the sheet moved down');
  grab.dispatch('pointerup', pointerEvent(180, 700, 11, 1020));
  assert.equal(drawer.hidden, true, 'the swipe down closed the sheet');
  assert.equal(dom.registry['sheet-scrim'].hidden, true, 'the scrim went away with the sheet');
});

test('a short movement snaps the sheet back instead of closing it', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  const drawer = dom.registry['camera-drawer'];
  const grab = dom.registry['sheet-grab'];
  dom.registry['features'].onclick();
  grab.dispatch('pointerdown', pointerEvent(180, 600, 12, 2000));
  grab.dispatch('pointermove', pointerEvent(180, 620, 12, 2010));
  grab.dispatch('pointerup', pointerEvent(180, 620, 12, 2020));
  assert.equal(drawer.hidden, false, 'the sheet stayed open');
});

test('the sheet of the settings does not cover the header and scrolls its rows', () => {
  const sheet = css.slice(css.indexOf('.sheet{'), css.indexOf('.sheet-body{'));
  assert.ok(sheet.includes('max-height:'), 'the sheet is limited in height: ' + sheet);
  const body = css.slice(css.indexOf('.sheet-body{'), css.indexOf('.sheet-grab{'));
  assert.ok(body.includes('overflow-y:auto'), 'the rows of the sheet scroll: ' + body);
  const markup = html.slice(html.indexOf('id="camera-drawer"'), html.indexOf('id="features-menu"'));
  assert.ok(markup.includes('id="sheet-body"'), 'the rows of the sheet are in a scrollable block');
  assert.ok(markup.indexOf('id="sheet-grab"') < markup.indexOf('id="sheet-body"'), 'the handle stays above the rows');
});

test('the stream and the engine are selects with a list, not plain text', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  dom.registry['features'].onclick();
  assert.equal(dom.registry['sheet-stream-options'].hidden, true, 'the list of the streams is closed');
  dom.registry['sheet-stream'].onclick();
  assert.equal(dom.registry['sheet-stream-options'].hidden, false, 'the control opens the list');
  dom.registry['sheet-stream-720'].textContent = 'Вход · 720p';
  dom.registry['sheet-stream-720'].onclick();
  assert.equal(dom.registry['sheet-stream-value'].textContent, 'Вход · 720p', 'the picked stream is shown');
  assert.equal(dom.registry['sheet-stream-options'].hidden, true, 'the list closed after the pick');
  dom.registry['sheet-engine'].onclick();
  assert.equal(dom.registry['sheet-engine-options'].hidden, false, 'the engine opens its list');
  // The PWA offers MSE and WebRTC; an engine that cannot be used stays dimmed in the list.
  assert.ok(dom.registry['sheet-engine-webrtc'].classList.contains('is-off'), 'WebRTC is dimmed');
  dom.registry['sheet-engine-value'].textContent = 'MSE';
  dom.registry['sheet-engine-webrtc'].onclick?.();
  assert.equal(dom.registry['sheet-engine-value'].textContent, 'MSE', 'the dimmed engine cannot be picked');
  dom.registry['sheet-engine-mse'].textContent = 'MSE';
  dom.registry['sheet-engine-mse'].onclick();
  assert.equal(dom.registry['sheet-engine-value'].textContent, 'MSE', 'the working engine is shown');

  dom.registry['width'].onclick();
  dom.registry['features'].onclick();
  dom.registry['menu-engine'].onclick();
  assert.equal(dom.registry['menu-engine-options'].hidden, false, 'the menu of the wide screen has the same selects');
  assert.ok(dom.registry['menu-engine-webrtc'].classList.contains('is-off'), 'the wide menu dims WebRTC as well');
  dom.registry['menu-engine-value'].textContent = 'MSE';
  dom.registry['menu-engine-webrtc'].onclick?.();
  assert.equal(dom.registry['menu-engine-value'].textContent, 'MSE', 'the dimmed engine cannot be picked there either');
});

test('the engine list holds the engines of the PWA, and MJPEG is the low bandwidth switch', () => {
  // In the PWA jsmpeg is the force low-bandwidth switch, not a technology choice.
  const sheet = html.slice(html.indexOf('id="sheet-engine-options"'), html.indexOf('id="sheet-engine-options"') + 600);
  assert.ok(!sheet.includes('MJPEG'), 'MJPEG is not an engine option');
  assert.ok(sheet.includes('id="sheet-engine-mse"'), 'MSE is an engine option');
  assert.ok(sheet.includes('id="sheet-engine-webrtc"'), 'WebRTC is an engine option');
  const menu = html.slice(html.indexOf('id="menu-engine-options"'), html.indexOf('id="menu-engine-options"') + 600);
  assert.ok(!menu.includes('MJPEG'), 'MJPEG is not an engine option in the wide menu either');
  assert.ok(html.includes('Низкая полоса'), 'the low bandwidth switch stays in the settings');
});

test('the wide layout keeps its own width instead of being shrunk to the phone', () => {
  const dom = createDom();
  const sandbox = {document: dom.document, window: dom.window, setTimeout: dom.setTimeout};
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  dom.registry['width'].onclick();
  assert.ok(!dom.device.style.zoom, 'the wide device is drawn as it is');
  assert.ok(css.includes('.device.wide{width:720px'), 'the wide layout is 720 px wide');
  dom.registry['width'].onclick();
  assert.ok(!dom.device.style.zoom, 'the narrow layout is not scaled either');
});

console.log(`${count} camera panel checks passed; not browser rendering or Android runtime verification`);
