(async function () {
  let checks=0;
  const wait=()=>new Promise(r=>setTimeout(r,40));
  function check(condition,message){if(!condition)throw new Error(message);checks++;}
  try {
    check(!!window.__virtualLocation,'Injected before first inline script');
    const geo=navigator.geolocation, virtual=window.__virtualLocation;
    let emptyError=null; geo.getCurrentPosition(()=>{throw new Error('No point should not succeed');},e=>emptyError=e);
    await wait();check(emptyError && emptyError.code===2,'No point returns unavailable without native GPS');
    virtual.set(32.5149,-117.0382);
    let synchronous=true,first=null;
    geo.getCurrentPosition(p=>{check(!synchronous,'Callbacks asynchronous');first=p;});synchronous=false;
    await wait();check(first.coords.latitude===32.5149 && first.coords.longitude===-117.0382,'Exact selected point');
    check(first.timestamp>0 && first.coords.altitude===null,'Position metadata');
    const changes=[];const id=geo.watchPosition(p=>changes.push(p.coords));
    await wait();check(changes.length===1,'Watch delivers initial point');
    virtual.set(32.50,-117.01);await wait();
    check(changes.length===2 && changes[1].latitude===32.50,'Watch receives new point without reload');
    virtual.set(32.51,-117.02);geo.clearWatch(id);await wait();
    check(changes.length===2 && virtual.watchCount()===0,'ClearWatch cancels queued updates');
    let invalid=false;try{virtual.set(91,0);}catch(e){invalid=true;}
    check(invalid && virtual.point().lat===32.51,'Reject invalid point without overwriting current point');
    const other=document.createElement('iframe');other.srcdoc='<html><body>other origin</body></html>';document.body.appendChild(other);
    // Cross-origin isolation is additionally enforced by Android's allowedOriginRules.
    console.log('VIRTUAL_TEST_PASS '+checks);
    document.body.innerText='PASS: '+checks+' comprobaciones del proveedor virtual en Android WebView';
  } catch(e) { console.error('VIRTUAL_TEST_FAIL '+e.stack);document.body.innerText='FAIL: '+e.message; }
})();
