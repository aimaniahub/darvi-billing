const CACHE='darvi-billing-v06-static';
const STATIC=['./','./index.html','./styles.css','./app.js','./legacy-vault.js','./manifest.webmanifest','./assets/Official Darvi Group Logo.jpeg','./assets/icon-192.png','./assets/icon-512.png'];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(c=>c.addAll(STATIC)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('darvi-billing-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 const u=new URL(event.request.url);if(event.request.method!=='GET'||u.origin!==self.location.origin||/\/(?:api|auth)\//.test(u.pathname))return;
 const staticAsset=STATIC.some(path=>new URL(path,self.registration.scope).pathname===u.pathname);
 if(!staticAsset)return;
 event.respondWith(fetch(event.request).then(res=>{if(res.ok){const copy=res.clone();caches.open(CACHE).then(c=>c.put(event.request,copy));}return res;}).catch(()=>caches.match(event.request)));
});
