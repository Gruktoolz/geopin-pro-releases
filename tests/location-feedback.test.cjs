const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function setup(loading=false){
 let timer=0;const timers=new Map(),events=[],handlers={};
 const layer={isLoading:()=>loading,once:(name,fn)=>handlers[name]=fn,off:(name,fn)=>{if(handlers[name]===fn)delete handlers[name];}};
 const context={window:{cobertura_fibra:layer},setTimeout:fn=>{timers.set(++timer,fn);return timer;},clearTimeout:id=>timers.delete(id)};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/location-feedback.js'),'utf8'),context);
 const observer=context.window.__createCoverageFeedback(e=>events.push(e));
 return {observer,events,emit(name){if(handlers[name])handlers[name]();},flush(){for(const [id,fn] of [...timers]){timers.delete(id);fn();}},request(endpoint,lat=32.5,lon=-117){const xhr={status:200,responseJSON:{STATUS:'200'}};observer.sent(xhr,{data:JSON.stringify({endpoint,params:{locacion:`${lat}, ${lon}`}})});return xhr;}};
}
let checks=0;function test(name,fn){fn();checks++;console.log('PASS '+name);}
test('validation alone never announces ready',()=>{const x=setup();x.observer.start(1,32.5,-117);x.observer.complete(x.request('geoinvmex'));x.flush();assert.equal(x.events.length,0);});
test('cached tiles finish immediately',()=>{const x=setup();x.observer.start(1,32.5,-117);x.observer.complete(x.request('geoinvcol'));assert.equal(x.events.length,1);assert.equal(x.events[0].ok,true);assert.equal(x.events[0].tiles_ms,0);});
test('loading tiles finish only on load',()=>{const x=setup(true);x.observer.start(1,32.5,-117);x.observer.complete(x.request('geoinvcol'));assert.equal(x.events.length,0);x.emit('load');assert.equal(x.events[0].map_state,'ready');x.flush();assert.equal(x.events.length,1);});
test('tile failures and timeout remain unknown',()=>{for(const trigger of ['tileerror','timeout']){const x=setup(true);x.observer.start(1,32.5,-117);x.observer.complete(x.request('geoinvcol'));trigger==='timeout'?x.flush():x.emit(trigger);assert.equal(x.events.length,1);assert.equal(x.events[0].map_state,'unknown');}});
test('duplicate completion produces one event',()=>{const x=setup();x.observer.start(1,32.5,-117);const a=x.request('geoinvcol'),b=x.request('geoinvcol');x.observer.complete(a);x.observer.complete(b);assert.equal(x.events.length,1);});
test('new selection invalidates requests and tile callbacks',()=>{const x=setup(true);x.observer.start(1,32.5,-117);const a=x.request('geoinvcol');x.observer.start(2,32.6,-117);x.observer.complete(a);assert.equal(x.events.length,0);x.observer.complete(x.request('geoinvcol',32.6));x.observer.start(3,32.7,-117);x.emit('load');x.flush();assert.equal(x.events.length,0);});
test('HTTP failures and validation rejection never succeed',()=>{for(const kind of ['http','validation']){const x=setup();x.observer.start(1,32.5,-117);const a=x.request(kind==='http'?'geoinvcol':'geoinvmex');if(kind==='http')a.status=500;else a.responseJSON.STATUS='204';x.observer.complete(a);assert.equal(x.events[0].ok,false);}});
test('colony 204 is not a transport failure',()=>{const x=setup();x.observer.start(1,32.5,-117);const a=x.request('geoinvcol');a.responseJSON.STATUS='204';x.observer.complete(a);assert.equal(x.events[0].ok,true);});
test('different coordinates cannot confirm the selected point',()=>{const x=setup();x.observer.start(1,32.5,-117);x.observer.complete(x.request('geoinvcol',33));assert.equal(x.events.length,0);});
console.log('PASS '+checks+' feedback scenarios');
