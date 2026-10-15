const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const assets=path.join(__dirname,'../app/src/main/assets');
function exercise(directory,legacy=false,origin='https://map.example'){
 const trace=[],timers=[],messages=[],listeners={};
 const mapListeners={};const container={appendChild(){trace.push('aim');},addEventListener(name,fn){listeners[name]=fn;}};
 const layer={options:{minZoom:10,minNativeZoom:10},_map:true,_onMoveEnd(){},redraw(){trace.push('redraw');}};
 const m={options:{minZoom:10},_layers:{one:layer},_zoomBoundLayers:{one:layer},getMinZoom(){return 10;},
 on(name,fn){mapListeners[name]=fn;trace.push('on:'+name);},off(name){trace.push('off:'+name);},
 getContainer:()=>container,_updateZoomLevels(){trace.push('zoomlevels');},zoomControl:{_updateDisabled(){trace.push('zoomcontrols');}},
 invalidateSize(){trace.push('resize');},getSize:()=>({divideBy:()=>[1,2]}),containerPointToLatLng:()=>({lat:20.5,lng:-103.2}),mouseEventToLatLng:()=>({lat:20.5,lng:-103.2})};
 const window={map:m,addEventListener(name,fn){listeners[name]=fn;},__createCoverageViewportGuard:()=>({cancel(){},start(...args){trace.push(['viewport',...args]);}}),__createCoverageFeedback:()=>({start(...args){trace.push(['feedback',...args]);}})};
 if(!legacy)window.__mapProvider={origin,blurElement:'domGeo',locateControl:'.leaflet-control-locate a'};
 const document={head:{appendChild(){trace.push('style');}},createElement:()=>({style:{},setAttribute(){}}),getElementById:()=>({addEventListener(name,fn){listeners['geo-'+name]=fn;}}),querySelector:()=>({click(){trace.push('locate');}})};
 const context={window,document,location:{origin,href:origin+'/'},navigator:{},setTimeout(fn,delay){timers.push({fn,delay});},Element:class{},PointChannel:{postMessage(s){messages.push(JSON.parse(s));}},URL};
 window.PointChannel=context.PointChannel;
 vm.createContext(context);
 function run(name){vm.runInContext(fs.readFileSync(path.join(directory,name),'utf8'),context,{filename:name});}
 run('virtual-location.js');if(!legacy)run('webview-optimizations.js');
 window.L={GridLayer:function(){},Util:{throttle:fn=>fn}};window.L.GridLayer.prototype.options={};
 run('coverage-adapter.js');assert.equal(window.__coverageAdapter.ready(),true);
 assert.equal(window.__coverageAdapter.query(20.5,-103.2,7),'requested');
 assert.equal(window.__coverageAdapter.center(),true);
 assert.equal(window.__virtualLocation.point().lat,20.5);
 assert.equal(m.getMinZoom(),5);assert.equal(layer.options.keepBuffer,24);assert.equal(layer.options.updateInterval,16);assert.equal(layer.options.updateWhenIdle,false);
 assert.deepEqual(timers.map(t=>t.delay),[150,400,800,1500,3000]);
 listeners['geo-blur']();listeners.resize();assert.deepEqual(timers.slice(-2).map(t=>t.delay),[250,250]);
 const options=JSON.parse(JSON.stringify(layer.options));
 return JSON.parse(JSON.stringify({trace,messages,options,earlyOptions:window.L.GridLayer.prototype.options,timings:timers.map(t=>t.delay)}));
}
if(require.main===module){
 const result=exercise(assets);assert.equal(result.messages[0].source,'center');
 // The generic optimization module must not execute on an unselected origin.
 const window={__mapProvider:{origin:'https://selected.example'}};
 vm.runInNewContext(fs.readFileSync(path.join(assets,'webview-optimizations.js'),'utf8'),{window,location:{origin:'https://other.example'}});
 assert.equal(window.__mapPerformance,undefined);
 console.log('PASS adapter query, center, layer configuration, timing, and origin isolation');
}
module.exports={exercise};
