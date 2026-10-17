const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('production Leaflet hook retains old tiles until current viewport loads', () => {
  const window = {__mapProvider: {origin: 'https://fixture.invalid'}};
  vm.runInNewContext(fs.readFileSync(__dirname + '/../app/src/main/assets/webview-optimizations.js', 'utf8'), {
    window, location: {origin: 'https://fixture.invalid'},
    document: {head: {appendChild() {}}, createElement() {return {};}}
  });
  let pruned = 0;
  function GridLayer() {}
  GridLayer.prototype.options = {};
  GridLayer.prototype._pruneTiles = function() {pruned++;};
  window.L = {GridLayer};
  const layer = new GridLayer();
  layer._tiles = {old: {current: false, loaded: true}, current: {current: true, loaded: false}};
  layer._pruneTiles();
  assert.equal(pruned, 0);
  layer._tiles.current.loaded = true;
  layer._pruneTiles();
  assert.equal(pruned, 1);
  window.L = window.L;
  layer._pruneTiles();
  assert.equal(pruned, 2, 'reassigning L does not wrap the prototype twice');
});
