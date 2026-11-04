// scripts/prepare-www.js
// Synchronizes frontend web assets to www/ for Capacitor Android build

const fs = require('fs');
const path = require('path');

const ROOT_DIR = path.resolve(__dirname, '..');
const WWW_DIR = path.join(ROOT_DIR, 'www');

const FILES_TO_COPY = [
  'index.html',
  'app.js',
  'thermal-printer.js',
  'cleanter-printer.js',
  'styles.css',
  'legacy-vault.js',
  'manifest.webmanifest',
  'sw.js'
];

const DIRS_TO_COPY = [
  'assets'
];

function copyFolderRecursiveSync(source, target) {
  if (!fs.existsSync(target)) {
    fs.mkdirSync(target, { recursive: true });
  }

  if (fs.lstatSync(source).isDirectory()) {
    const files = fs.readdirSync(source);
    files.forEach(file => {
      const curSource = path.join(source, file);
      const curTarget = path.join(target, file);
      if (fs.lstatSync(curSource).isDirectory()) {
        copyFolderRecursiveSync(curSource, curTarget);
      } else {
        fs.copyFileSync(curSource, curTarget);
      }
    });
  }
}

if (!fs.existsSync(WWW_DIR)) {
  fs.mkdirSync(WWW_DIR, { recursive: true });
}

FILES_TO_COPY.forEach(file => {
  const src = path.join(ROOT_DIR, file);
  const dest = path.join(WWW_DIR, file);
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, dest);
    console.log(`Copied ${file} -> www/`);
  }
});

DIRS_TO_COPY.forEach(dir => {
  const src = path.join(ROOT_DIR, dir);
  const dest = path.join(WWW_DIR, dir);
  if (fs.existsSync(src)) {
    copyFolderRecursiveSync(src, dest);
    console.log(`Copied directory ${dir} -> www/`);
  }
});

console.log('✓ www/ directory prepared successfully for Capacitor!');

