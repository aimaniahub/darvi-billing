const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');

const HTTP_PORT = process.env.PORT || 8000;
const HTTPS_PORT = process.env.HTTPS_PORT || 8443;

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

function requestHandler(req, res) {
  let cleanUrl = req.url.split('?')[0];
  try {
    cleanUrl = decodeURIComponent(cleanUrl);
  } catch (e) {}

  if (cleanUrl === '/' || cleanUrl === '') {
    cleanUrl = '/index.html';
  }

  // Handle local network IP query for mobile bridge guidance
  if (cleanUrl === '/api/network-info') {
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*'
    });
    res.end(JSON.stringify({
      localIp: getLocalIp(),
      httpPort: HTTP_PORT,
      httpsPort: HTTPS_PORT,
      url: `http://${getLocalIp()}:${HTTP_PORT}`
    }));
    return;
  }

  // Handle favicon.ico fallback gracefully
  if (cleanUrl === '/favicon.ico') {
    cleanUrl = '/assets/icon-192.png';
  }

  const safePath = path.normalize(cleanUrl).replace(/^(\.\.[\/\\])+/, '');
  const filePath = path.join(__dirname, safePath);

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found: ' + cleanUrl);
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.writeHead(200, {
      'Content-Type': contentType,
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-cache'
    });

    const stream = fs.createReadStream(filePath);
    stream.pipe(res);
  });
}

function getLocalIp() {
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === 'IPv4' && !net.internal) {
        return net.address;
      }
    }
  }
  return '127.0.0.1';
}

async function getOrGenerateSslCerts() {
  const sslDir = path.join(__dirname, '.ssl');
  const certFile = path.join(sslDir, 'cert.json');

  if (fs.existsSync(certFile)) {
    try {
      const data = JSON.parse(fs.readFileSync(certFile, 'utf8'));
      if (data.cert && data.key) return data;
    } catch (e) {}
  }

  try {
    const selfsigned = require('selfsigned');
    const localIp = getLocalIp();
    const attrs = [{ name: 'commonName', value: localIp }];
    const pems = await selfsigned.generate(attrs, {
      days: 365,
      algorithm: 'sha256',
      keySize: 2048,
      extensions: [
        {
          name: 'subjectAltName',
          altNames: [
            { type: 2, value: 'localhost' },
            { type: 7, ip: '127.0.0.1' },
            { type: 7, ip: localIp }
          ]
        }
      ]
    });

    const certData = { cert: pems.cert, key: pems.private };
    if (!fs.existsSync(sslDir)) fs.mkdirSync(sslDir, { recursive: true });
    fs.writeFileSync(certFile, JSON.stringify(certData), 'utf8');
    return certData;
  } catch (err) {
    console.warn('Could not generate selfsigned SSL cert:', err.message);
    return null;
  }
}

async function startServers() {
  const localIp = getLocalIp();

  // 1. HTTP Server (standard port 8000)
  const httpServer = http.createServer(requestHandler);
  httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
    console.log(`\n======================================================`);
    console.log(` Darvi Billing Server running!`);
    console.log(` Local HTTP:    http://localhost:${HTTP_PORT}`);
    console.log(` Network HTTP:  http://${localIp}:${HTTP_PORT}`);
  });

  // 2. HTTPS Server (port 8443) for Web Bluetooth Secure Context on Mobile
  try {
    const ssl = await getOrGenerateSslCerts();
    if (ssl) {
      const httpsServer = https.createServer({ key: ssl.key, cert: ssl.cert }, requestHandler);
      httpsServer.listen(HTTPS_PORT, '0.0.0.0', () => {
        console.log(` ------------------------------------------------------`);
        console.log(` MOBILE WEB BLUETOOTH SECURE CONTEXT (HTTPS):`);
        console.log(` Open on Mobile: https://${localIp}:${HTTPS_PORT}`);
        console.log(` (Bypasses Chrome Bluetooth blocks on mobile devices)`);
        console.log(`======================================================\n`);
      });
    }
  } catch (sslErr) {
    console.warn('HTTPS server start skipped:', sslErr.message);
  }
}

startServers();
