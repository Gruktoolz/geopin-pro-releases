(async function () {
  const results=[];
  function check(c,m){if(!c)throw new Error(m);results.push(m);}
  async function until(predicate,label){const deadline=Date.now()+30000;while(Date.now()<deadline){if(predicate())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Timeout: '+label);}
  function bluePoint(){let point=null;window.map.eachLayer(layer=>{if(layer._icon && layer._icon.classList.contains('leaflet-control-locate-location'))point=layer.getLatLng();});return point;}
  const replies=[];
  try {
    check(!!window.__virtualLocation,'Virtual provider exists in live website');
    check(!!window.__coverageAdapter,'Website adapter loaded');
    await until(()=>window.map && window.$ && document.querySelector('.leaflet-control-locate a'),'Website map ready');
    const aim=document.getElementById('coverage-center-aim');
    check(!!aim,'Center crosshair exists');
    const aimRect=aim.getBoundingClientRect(),mapRect=map.getContainer().getBoundingClientRect();
    check(Math.abs((aimRect.left+aimRect.width/2)-(mapRect.left+mapRect.width/2))<1 && Math.abs((aimRect.top+aimRect.height/2)-(mapRect.top+mapRect.height/2))<1,'Crosshair exactly centered on map');
    check(getComputedStyle(aim).pointerEvents==='none','Crosshair does not block map gestures');
    const centerPoint=map.containerPointToLatLng([map.getSize().x/2,map.getSize().y/2]);
    check(map.latLngToContainerPoint(centerPoint).distanceTo(map.getSize().divideBy(2))<=0.71,'Selected center coordinate projects back to crosshair within a rendered pixel');
    $(document).ajaxSuccess(function(event,xhr,settings,data){
      try{const request=JSON.parse(settings.data);if(request.endpoint==='geoinvcol')replies.push({point:request.params.locacion,data});}catch(ignored){}
    });
    const page=document.documentElement;
    let pointEvents=0;map.on('locationfound',()=>pointEvents++);
    const a={lat:32.5149,lon:-117.0382},b={lat:32.5283,lon:-117.0223};
    check(__coverageAdapter.query(a.lat,a.lon)==='requested','First virtual query requested');
    await until(()=>bluePoint() && replies.length>=1,'First blue point and server colony response');
    check(Math.abs(bluePoint().lat-a.lat)<1e-7 && Math.abs(bluePoint().lng-a.lon)<1e-7,'Native website blue point matches first coordinates');
    check(String(replies[0].data.STATUS)==='200','Website accepted first colony lookup');
    await new Promise(r=>setTimeout(r,750));
    check(map.getZoom()===18,'First opening stays at street zoom after colony response');
    check(map.latLngToContainerPoint([a.lat,a.lon]).distanceTo(map.getSize().divideBy(2))<=1,'First selected point remains centered after response');
    const count=replies.length;
    check(__coverageAdapter.query(b.lat,b.lon)==='requested','Second virtual query requested');
    await until(()=>replies.length>count && Math.abs(bluePoint().lat-b.lat)<1e-7,'Updated blue point and colony response');
    check(Math.abs(bluePoint().lng-b.lon)<1e-7,'Native blue point moved to second coordinates');
    check(String(replies[replies.length-1].data.STATUS)==='200','Website accepted second colony lookup');
    await new Promise(r=>setTimeout(r,750));
    check(map.getZoom()===18,'Changing colony stays at street zoom after response');
    check(map.latLngToContainerPoint([b.lat,b.lon]).distanceTo(map.getSize().divideBy(2))<=1,'New selected point remains centered after colony change');
    check(replies.length===2 && replies[0].data.colonia.gid!==replies[1].data.colonia.gid,'Two different colonies queried once each without a second confirmation');
    check(page===document.documentElement && pointEvents===2,'Two location changes in same document without reload');
    check(__virtualLocation.watchCount()===1,'Single website watcher handles updates');
    console.log('INTEGRATION_TEST_PASS '+JSON.stringify({checks:results.length,points:[a,b],label:document.getElementById('domGeo').value}));
  } catch(e){console.error('INTEGRATION_TEST_FAIL '+e.stack+' replies='+JSON.stringify(replies));}
})();
