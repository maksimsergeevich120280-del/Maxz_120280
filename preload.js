'use strict';
const { contextBridge, ipcRenderer } = require('electron');
const api = {
  isDesktopApp: true,
  getDataPath: () => ipcRenderer.invoke('data:get-path'),
  setDataPath: filePath => ipcRenderer.invoke('data:set-path', filePath),
  askDataLocation: () => ipcRenderer.invoke('data:ask-location'),
  readDataFile: filePath => ipcRenderer.invoke('data:read', filePath),
  getDataFileMeta: filePath => ipcRenderer.invoke('data:meta', filePath),
  writeDataFile: (filePath, document, expectedRevision, expectedHash) => ipcRenderer.invoke('data:write', filePath, document, expectedRevision, expectedHash),
  acquireEditLock: (filePath, userLabel) => ipcRenderer.invoke('data:acquire-lock', filePath, userLabel),
  releaseEditLock: filePath => ipcRenderer.invoke('data:release-lock', filePath),
  json: {
    chooseExisting: () => ipcRenderer.invoke('json:choose-existing'),
    read: filePath => ipcRenderer.invoke('json:read', filePath),
    write: (filePath, content) => ipcRenderer.invoke('json:write', filePath, content),
    stat: filePath => ipcRenderer.invoke('json:stat', filePath)
  }
};
contextBridge.exposeInMainWorld('electronAPI', api);
contextBridge.exposeInMainWorld('api', api);
contextBridge.exposeInMainWorld('journalUpdater', {
  currentVersion: '129', currentVersionCode: 129,
  chooseFolder: () => ipcRenderer.invoke('update:choose-folder'),
  getFolder: () => ipcRenderer.invoke('update:get-folder'),
  openFolder: () => ipcRenderer.invoke('update:open-folder'),
  check: () => ipcRenderer.invoke('update:check'),
  install: () => ipcRenderer.invoke('update:install'),
  rollback: () => ipcRenderer.invoke('update:rollback')
});
