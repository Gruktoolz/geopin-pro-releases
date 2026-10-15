const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
function setup(map,windowExtras={}){
 const events=[],hooks={},window={__mapProvider:{origin:'https://provider.example'},__virtualLocation:{set(lat,lon){events.push({virtual:[lat,lon]});}},__mapPerformance:{attach(){events.push({performance:true});}},...windowExtras};
 if(map)window.map=map;
 const context={window,location:{origin:'https://provider.example'},PointChannel:{postMessage(s){events.push(JSON.parse(s));}}};window.PointChannel=context.PointChannel;
 vm.createContext(context);
 function run(name){vm.runInContext(fs.readFileSync(path.join(assets,name),'utf8'),context);}
 run('map-discovery.js');
 if(map)map.on=(name,fn)=>hooks[name]=fn;
 return {window,context,events,hooks,run,adapter(){run('map-adapter.js');return window.__coverageAdapter;}};
}
function mapWith(methods){return {on(){},...methods};}
// Leaflet's map may be in a closure, without window.map. Discovery observes its normal init hook.
let initHook;
const x=setup(null,{L:{Map:{addInitHook(fn){initHook=fn;}}}});
let selected;
const leaflet=mapWith({setView(point,zoom,options){selected={point,zoom,options};},containerPointToLatLng:()=>({lat:20.5,lng:-103.2}),getSize:()=>({divideBy:()=>[0,0]})});
initHook.call(leaflet);const la=x.adapter();assert.equal(la.ready(),true);assert.equal(la.query(20.5,-103.2,11),'requested');
assert.deepEqual(JSON.parse(JSON.stringify(selected)),{point:[20.5,-103.2],zoom:18,options:{animate:false}});assert.equal(la.center(),true);assert.equal(x.events.at(-1).source,'center');
assert.equal(x.events.filter(e=>e.performance).length,1);la.ready();assert.equal(x.events.filter(e=>e.performance).length,1);
console.log('PASS Leaflet discovery without a global map, positioning and center');
// MapLibre/Mapbox expose longitude before latitude in their camera API.
let camera;const gl=mapWith({jumpTo(value){camera=value;},getCenter:()=>({lat:19.4,lng:-99.1})});
const y=setup(gl),ga=y.adapter();assert.equal(ga.query(19.4,-99.1,12),'requested');assert.deepEqual(JSON.parse(JSON.stringify(camera)),{center:[-99.1,19.4],zoom:18});
assert.equal(ga.center(),true);assert.equal(y.events.at(-1).lat,19.4);y.hooks.click({lngLat:{lat:21, lng:-100}});assert.equal(y.events.at(-1).lon,-100);
assert.equal(y.events.some(e=>e.performance),false);assert.equal(ga.query(NaN,-99.1,13),'invalid-point');
console.log('PASS GL coordinate order, center, clicks and invalid input');
// OpenLayers uses the view projection; never assume longitude/latitude are pixel coordinates.
for(const code of ['EPSG:3857','EPSG:4326']){
 let center,zoom;
 const view={getProjection:()=>({getCode:()=>code}),setCenter(v){center=v;},getCenter:()=>center,setZoom(v){zoom=v;}};
 const ol=mapWith({getView:()=>view,getViewport:()=>({})}),z=setup(ol),oa=z.adapter();
 assert.equal(oa.query(20.5,-103.2,14),'requested');assert.equal(zoom,18);assert.equal(oa.center(),true);
 const result=z.events.at(-1);assert.ok(Math.abs(result.lat-20.5)<1e-6);assert.ok(Math.abs(result.lon+103.2)<1e-6);
}
console.log('PASS OpenLayers Mercator/geographic round trip and center');
const unsupported=setup(mapWith({getView:()=>({getProjection:()=>({getCode:()=> 'EPSG:32614'})}),getViewport:()=>({})}));
assert.equal(unsupported.adapter().query(20.5,-103.2,15),'not-ready');assert.equal(unsupported.events.some(e=>e.type==='location-result'),false);
const missing=setup(null);assert.equal(missing.adapter().ready(),false);assert.equal(missing.window.__coverageAdapter.query(20.5,-103.2),'not-ready');
console.log('PASS unsupported map/projection never claims successful positioning');

// A normal UMD namespace is exported before its Map class; do not miss closed-over instances.
const lateNamespace={},late=setup(null,{L:lateNamespace});let lateHook;
lateNamespace.Map={addInitHook(fn){lateHook=fn;}};assert.equal(typeof lateHook,'function');
lateHook.call(mapWith({setView(){},containerPointToLatLng(){return {lat:20, lng:-100};},getSize:()=>({divideBy:()=>[0,0]})}));
assert.equal(late.adapter().ready(),true);
console.log('PASS late Leaflet UMD Map export is discovered before the page constructs a map');
