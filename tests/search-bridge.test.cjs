const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const script=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/search-bridge.js'),'utf8');
function fixture(origin='https://selected.example'){
 const messages=[],timers=[],listeners={},remote=[],updates=[];let responses=0;
 const field={value:'',addEventListener:(n,f)=>listeners[n]=f,blur(){document.activeElement=null;}};
 const widget={options:{source(req,cb){remote.push({req,cb});}},__response(rows){updates.push(rows);}};
 const wrapper={autocomplete(action,...args){
  if(action==='instance')return widget;if(action==='option'){Object.assign(widget.options,args[0]);return this;}
  if(action==='search'){widget.options.source({term:args[0]},rows=>{responses++;updates.push(rows);});return this;}
  if(action==='widget')return {is:()=>false};return this;
 }};
 const $=()=>wrapper;$.fn={autocomplete(){}};
 const document={getElementById:()=>field,activeElement:field};
 const window={__mapProvider:{origin:'https://selected.example',blurElement:'domGeo'},jQuery:$,PointChannel:{postMessage:s=>messages.push(JSON.parse(s))}};
 vm.runInNewContext(script,{window,document,location:{origin},setTimeout(fn){timers.push(fn);return fn;},clearTimeout(fn){const i=timers.indexOf(fn);if(i>=0)timers.splice(i,1);}});
 return {window,document,field,widget,messages,timers,remote,updates,listeners,get responses(){return responses;}};
}
const f=fixture(),m=f.window.__searchMirror;
assert.equal(m.ready(),true);assert.equal(f.widget.options.minLength,1);
f.widget.options.source({term:'V'},rows=>{f.updates.push(rows);});
assert.equal(f.messages.at(-1).text,'V');
m.request('V',1,true);m.results('V',1,[{id:0,label:'Valle Verde',source:'INEGI'}]);
assert.equal(f.updates.at(-1)[0].label,'Valle Verde');assert.equal(f.timers.length,0,'single character stays offline');
f.widget.options.source({term:'Villa'},rows=>{f.updates.push(rows);});
m.request('Villa',2,true);m.results('Villa',1,[{id:0,label:'stale'}]);assert.notEqual(f.updates.at(-1)[0]?.label,'stale');
f.timers.shift()();assert.equal(f.remote.length,1);
f.remote[0].cb([{label:'Villa Fontana, Tijuana',locacion:'32.5,-117'}]);assert.equal(f.messages.at(-1).type,'search-results');
m.results('Villa',2,[{id:1,label:'Villa Fontana',source:'MEGA'}]);m.results('Villa',2,[{id:1,label:'Villa Fontana',source:'MEGA'}]);
assert.equal(f.updates.at(-1)[0].label,'Villa Fontana');
f.widget.options.select({}, {item:{id:1,label:'Villa Fontana'}});assert.equal(f.messages.at(-1).type,'search-select');assert.equal(f.messages.at(-1).generation,2);
m.request('Tijuana',3,false);assert.equal(f.field.value,'Tijuana');m.selected('Nueva selección');assert.equal(f.field.value,'Nueva selección');
while(f.timers.length)f.timers.shift()();assert.equal(f.remote.length,1,'selected cancels outstanding request');
assert.equal(fixture('https://other.example').window.__searchMirror,undefined,'origin stays isolated');
// Repeated enrichments use __response; jQuery's request callback must complete only once.
const g=fixture();let count=0;g.widget.options.source({term:'A'},()=>count++);g.window.__searchMirror.request('A',1,true);
g.window.__searchMirror.results('A',1,[]);g.window.__searchMirror.results('A',1,[{id:0,label:'Agua Caliente'}]);assert.equal(count,1);
g.widget.options.source({term:'AB'},()=>count++);g.widget.options.source({term:'ABC'},()=>count++);assert.equal(count,2,'superseded callback completed');g.window.__searchMirror.dismiss();assert.equal(count,3,'dismiss releases pending widget request');
console.log('PASS shared site field, first-character offline predictions, source enrichment, generations, cancellation, selection, callback balance and origin isolation');
