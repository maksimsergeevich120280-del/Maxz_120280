'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  isDesktopApp: true,
  getDataPath: () => ipcRenderer.invoke('data:get-path'),
  setDataPath: (filePath) => ipcRenderer.invoke('data:set-path', filePath),
  askDataLocation: (allowCreate) => ipcRenderer.invoke('data:choose-location', !!allowCreate),
  readDataFile: (filePath) => ipcRenderer.invoke('data:read', filePath),
  writeDataFile: (filePath, doc, expectedRevision) => ipcRenderer.invoke('data:write', filePath, doc, expectedRevision),
  getDataFileMeta: (filePath) => ipcRenderer.invoke('data:meta', filePath)
});

contextBridge.exposeInMainWorld('journalUpdater', {
  currentVersion: '1.0.0',
  currentVersionCode: 127,
  getFolder: () => ipcRenderer.invoke('updates:get-folder'),
  chooseFolder: () => ipcRenderer.invoke('updates:choose-folder'),
  check: (unused) => ipcRenderer.invoke('updates:check'),
  install: (unused) => ipcRenderer.invoke('updates:install'),
  rollback: () => ipcRenderer.invoke('updates:rollback'),
  openFolder: () => ipcRenderer.invoke('updates:open-folder')
});
