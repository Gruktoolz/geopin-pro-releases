const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/viewport-stability.js'),'utf8');
function setup(){
  let time=0,next=1;const timers=new Map();
  function schedule(fn,delay=0){const id=next++;timers.set(id,{at:time+delay,fn});return id;}
  function advance(ms){const end=time+ms;let rounds=0;while(true){const entries=[...timers].filter(([,v])=>v.at<=end).sort((a,b)=>a[1].at-b[1].at);if(!entries.length)break;if(++rounds>1000)throw new Error('Unbounded correction loop');const [id,v]=entries[0];timers.delete(id);time=v.at;v.fn();}time=end;}
  const handlers=new Map();
  const map={lat:0,lon:0,zoom:5,moves:0,
    on(names,fn){for(const name of names.split(' ')){if(!handlers.has(name))handlers.set(name,[]);handlers.get(name).push(fn);}},
    fire(name){for(const fn of handlers.get(name)||[])fn();},
    getZoom(){return this.zoom;},getSize(){return {divideBy(){return {x:100,y:100};}};},
    latLngToContainerPoint([lat,lon]){const self=this;return {distanceTo(){return Math.hypot(lat-self.lat,lon-self.lon)*1000;}};},
    stop(){},setView([lat,lon],zoom){this.lat=lat;this.lon=lon;this.zoom=zoom;this.moves++;this.fire('zoomend');this.fire('moveend');}
  };
  const context={window:{},setTimeout:schedule,clearTimeout:id=>timers.delete(id),requestAnimationFrame:fn=>schedule(fn,16),cancelAnimationFrame:id=>timers.delete(id)};
  vm.runInNewContext(source,context);
  return {map,advance,guard:context.window.__createCoverageViewportGuard(map,{quietMs:400,maxMs:30000})};
}
let checks=0;
function test(name,fn){fn();checks++;console.log('PASS '+name);}
test('Slow first colony response cannot leave map zoomed out',()=>{
  const {guard,map,advance}=setup();guard.start(32.5,-117,18);guard.requestStarted('first');
  advance(5000);assert.equal(guard.active(),true);
  map.setView([32.6,-117.1],13);guard.requestFinished('first');advance(16);
  assert.equal(map.zoom,18);assert.equal(map.lat,32.5);assert.equal(map.lon,-117);
  advance(400);assert.equal(guard.active(),false);
});
test('Chained validation and colony requests hold view until both finish',()=>{
  const {guard,map,advance}=setup();guard.start(32.5,-117,18);guard.requestStarted('validation');guard.requestStarted('colony');guard.requestFinished('validation');advance(1000);
  assert.equal(guard.active(),true);map.setView([31,-116],12);guard.requestFinished('colony');advance(500);assert.equal(map.zoom,18);assert.equal(guard.active(),false);
});
test('Late older response cannot move viewport away from latest selected point',()=>{
  const {guard,map,advance}=setup();guard.start(32.5,-117,18);guard.requestStarted('old');
  guard.start(32.6,-117.1,18);guard.requestStarted('new');guard.requestFinished('new');advance(500);
  assert.equal(guard.active(),true);map.setView([32.5,-117],12);guard.requestFinished('old');advance(500);
  assert.equal(map.lat,32.6);assert.equal(map.lon,-117.1);assert.equal(map.zoom,18);
});
test('User movement cancels correction without snapping back',()=>{
  const {guard,map,advance}=setup();guard.start(32.5,-117,18);guard.requestStarted('pending');guard.cancel();map.setView([33,-118],16);guard.requestFinished('pending');advance(1000);
  assert.equal(map.lat,33);assert.equal(map.zoom,16);assert.equal(guard.active(),false);
});
test('Failed request completion releases guard normally',()=>{
  const {guard,advance}=setup();guard.start(32.5,-117,18);guard.requestStarted('failed');guard.requestFinished('failed');advance(500);assert.equal(guard.active(),false);
});
test('Hanging request has a bounded guard lifetime',()=>{
  const {guard,map,advance}=setup();guard.start(32.5,-117,18);guard.requestStarted('hanging');advance(30000);assert.equal(guard.active(),false);map.setView([33,-118],12);advance(500);assert.equal(map.zoom,12);
});
test('No response or repeated settled events cannot cause a correction loop',()=>{
  const {guard,map,advance}=setup();guard.start(32.5,-117,18);advance(30000);assert.equal(guard.active(),false);assert.equal(map.moves,1);
});
console.log('PASS '+checks+' viewport stability scenarios');
