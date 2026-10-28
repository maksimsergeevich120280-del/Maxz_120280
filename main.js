'use strict';
const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');

const APP_VERSION_CODE = 128;
const APP_VERSION = app.getVersion();
let mainWindow;
let settings = {};
let pendingUpdate = null;
const settingsPath = () => path.join(app.getPath('userData'), 'journal-settings.json');
const appDataDir = () => path.join(app.getPath('userData'), 'journal-app');
const activeHtmlPath = () => path.join(appDataDir(), 'journal_step127.html');
const backupDir = () => path.join(app.getPath('userData'), 'update-backups');

async function readJson(filePath) {
  return JSON.parse(await fsp.readFile(filePath, 'utf8'));
}
async function writeJsonAtomic(filePath, value) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  const tmp = `${filePath}.${process.pid}.${crypto.randomBytes(5).toString('hex')}.tmp`;
  await fsp.writeFile(tmp, JSON.stringify(value, null, 2), 'utf8');
  await fsp.rename(tmp, filePath);
}
async function loadSettings() {
  try { settings = await readJson(settingsPath()); } catch (_) { settings = {}; }
  if (!settings || typeof settings !== 'object') settings = {};
}
async function saveSettings() { await writeJsonAtomic(settingsPath(), settings); }
async function ensureActiveHtml() {
  await fsp.mkdir(appDataDir(), { recursive: true });
  try { await fsp.access(activeHtmlPath()); }
  catch (_) {
    const bundled = app.isPackaged
      ? path.join(process.resourcesPath, 'journal_step127.html')
      : path.join(__dirname, 'journal_step127.html');
    await fsp.copyFile(bundled, activeHtmlPath());
  }
}
function validPath(p) { return typeof p === 'string' && p.trim().length > 0; }
function validateJournalDoc(doc) {
  return !!(doc && doc.format === 'ops-journal-shared-v1' && doc.payload &&
    typeof doc.payload === 'object' && typeof doc.payload.baimskaya_ops_journal_v1 === 'string');
}
async function makeWindow() {
  await ensureActiveHtml();
  mainWindow = new BrowserWindow({
    width: 1440, height: 920, minWidth: 1000, minHeight: 650,
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: false }
  });
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  await mainWindow.loadFile(activeHtmlPath());
}

ipcMain.handle('data:get-path', () => settings.dataPath || '');
ipcMain.handle('data:set-path', async (_event, p) => {
  if (p !== '' && !validPath(p)) throw new Error('Некорректный путь к JSON-файлу.');
  settings.dataPath = p || '';
  await saveSettings();
  return settings.dataPath;
});
ipcMain.handle('data:choose-location', async (_event, allowCreate) => {
  const opts = { title: 'Выбрать JSON-файл журнала', defaultPath: settings.dataPath || app.getPath('documents'), filters: [{ name: 'JSON', extensions: ['json'] }] };
  const opened = await dialog.showOpenDialog(mainWindow, { ...opts, properties: ['openFile'] });
  if (!opened.canceled && opened.filePaths[0]) return { action: 'open', path: opened.filePaths[0] };
  if (!allowCreate) return { action: 'cancel' };
  const saved = await dialog.showSaveDialog(mainWindow, { ...opts, title: 'Создать JSON-файл журнала', defaultPath: settings.dataPath || path.join(app.getPath('documents'), 'journal-data.json') });
  if (saved.canceled || !saved.filePath) return { action: 'cancel' };
  try { await fsp.access(saved.filePath); return { action: 'open', path: saved.filePath }; }
  catch (_) { return { action: 'create', path: saved.filePath }; }
});
ipcMain.handle('data:read', async (_event, filePath) => {
  if (!validPath(filePath)) throw new Error('Путь к JSON не указан.');
  const doc = await readJson(filePath);
  if (!validateJournalDoc(doc)) throw new Error('Файл не распознан как JSON-файл журнала.');
  return doc;
});
ipcMain.handle('data:meta', async (_event, filePath) => {
  try { const stat = await fsp.stat(filePath); return { exists: stat.isFile(), size: stat.size, modifiedAt: stat.mtime.toISOString() }; }
  catch (_) { return { exists: false }; }
});
ipcMain.handle('data:write', async (_event, filePath, doc, expectedRevision) => {
  if (!validPath(filePath)) throw new Error('Путь к JSON не указан.');
  if (!validateJournalDoc(doc)) throw new Error('Данные не распознаны как JSON-файл журнала.');
  let current = null;
  try { current = await readJson(filePath); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  const actualRevision = current && Number.isFinite(Number(current.revision)) ? Number(current.revision) : 0;
  if (actualRevision !== Number(expectedRevision || 0)) {
    const err = new Error(`Revision conflict: ожидалась версия ${Number(expectedRevision || 0)}, текущая версия ${actualRevision}.`);
    err.code = 'REVISION_CONFLICT'; throw err;
  }
  const now = new Date().toISOString();
  const out = { ...doc, format: 'ops-journal-shared-v1', formatVersion: 2,
    revision: actualRevision + 1,
    createdAt: (current && current.createdAt) || doc.createdAt || now,
    createdBy: (current && current.createdBy) || doc.createdBy || 'Не указано',
    updatedAt: now, updatedBy: doc.updatedBy || 'Пользователь' };
  await writeJsonAtomic(filePath, out);
  return { revision: out.revision, createdAt: out.createdAt, createdBy: out.createdBy, updatedAt: out.updatedAt, updatedBy: out.updatedBy };
});

ipcMain.handle('updates:get-folder', async () => {
  const folder = settings.updateFolder || '';
  return { folder, folderName: folder ? path.basename(folder) : '', canRollback: await hasBackup() };
});
ipcMain.handle('updates:choose-folder', async () => {
  const r = await dialog.showOpenDialog(mainWindow, { title: 'Выбрать общую папку обновлений', defaultPath: settings.updateFolder || app.getPath('documents'), properties: ['openDirectory'] });
  if (r.canceled || !r.filePaths[0]) return { canceled: true };
  settings.updateFolder = r.filePaths[0]; await saveSettings();
  return { canceled: false, folder: settings.updateFolder, folderName: path.basename(settings.updateFolder), canRollback: await hasBackup() };
});
ipcMain.handle('updates:check', async () => {
  const folder = settings.updateFolder;
  if (!folder) throw new Error('Сначала выбери папку обновлений.');
  const manifest = await readJson(path.join(folder, 'latest.json'));
  if (!manifest || !Number.isFinite(Number(manifest.versionCode)) || typeof manifest.file !== 'string') throw new Error('Файл latest.json имеет неверный формат.');
  const source = path.resolve(folder, manifest.file);
  if (path.dirname(source) !== path.resolve(folder)) throw new Error('Недопустимый путь к пакету обновления.');
  await fsp.access(source);
  pendingUpdate = { manifest, source };
  const installedCode = Number(settings.installedVersionCode || APP_VERSION_CODE);
  return { manifest, available: Number(manifest.versionCode) > installedCode, canRollback: await hasBackup() };
});
ipcMain.handle('updates:install', async () => {
  if (!pendingUpdate) throw new Error('Сначала нажми «Проверить обновления».');
  const code = Number(pendingUpdate.manifest.versionCode);
  const installedCode = Number(settings.installedVersionCode || APP_VERSION_CODE);
  if (!(code > installedCode)) throw new Error('Новая версия не найдена.');
  const ext = path.extname(pendingUpdate.source).toLowerCase();
  if (ext !== '.html') throw new Error('В этом пакете разрешено обновлять только HTML-файл журнала.');
  const incoming = await fsp.readFile(pendingUpdate.source);
  if (incoming.length < 100000) throw new Error('Файл обновления слишком мал; установка отменена.');
  await fsp.mkdir(backupDir(), { recursive: true });
  const backupPath = path.join(backupDir(), `journal_step127_${Date.now()}.html`);
  await fsp.copyFile(activeHtmlPath(), backupPath);
  const nextPath = activeHtmlPath() + '.new';
  await fsp.writeFile(nextPath, incoming);
  await fsp.rename(nextPath, activeHtmlPath());
  settings.installedVersionCode = code; settings.installedVersion = String(pendingUpdate.manifest.version || code); settings.lastUpdateBackup = backupPath;
  await saveSettings();
  app.relaunch(); app.exit(0);
  return { version: settings.installedVersion };
});
ipcMain.handle('updates:rollback', async () => {
  const backup = settings.lastUpdateBackup;
  if (!backup) throw new Error('Нет сохранённой резервной копии для отката.');
  await fsp.access(backup);
  const nextPath = activeHtmlPath() + '.rollback';
  await fsp.copyFile(backup, nextPath);
  await fsp.rename(nextPath, activeHtmlPath());
  settings.lastUpdateBackup = ''; await saveSettings();
  app.relaunch(); app.exit(0);
  return { version: 'предыдущая версия' };
});
ipcMain.handle('updates:open-folder', async () => {
  const folder = settings.updateFolder || '';
  if (!folder) throw new Error('Папка обновлений ещё не выбрана.');
  await shell.openPath(folder); return { folder };
});
async function hasBackup() {
  try { if (settings.lastUpdateBackup) { await fsp.access(settings.lastUpdateBackup); return true; } } catch (_) {}
  return false;
}

app.whenReady().then(async () => { await loadSettings(); await makeWindow(); app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) makeWindow(); }); });
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
