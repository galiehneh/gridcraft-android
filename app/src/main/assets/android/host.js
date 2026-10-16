// Android host page: frames the GridCraft web app in host mode (?host=parent) and relays its
// postMessage events to the native GridCraftAndroid bridge. Also exposes gcBack/gcPaste/gcKey
// for native code (evaluateJavascript).
(function () {
  'use strict';

  var noop = function () { return ''; };
  var native = window.GridCraftAndroid || {
    appReady: noop, appFailed: noop, setDirty: noop, beginSave: noop, beginPrint: noop,
    appendChunk: noop, finishSave: noop, abortSave: noop
  };
  var frame = document.getElementById('app');
  // Multiple of 3 so every base64 slice is self-contained.
  var CHUNK_BYTES = 3 * 348160;
  var PDF_MIME = 'application/pdf';
  var dirty = false;
  var queue = Promise.resolve();

  var params = new URLSearchParams(location.search);
  var file = params.get('file');
  frame.src = '/index.html?host=parent' + (file ? '&file=' + encodeURIComponent(file) : '');
  frame.addEventListener('load', hookPrint);

  window.addEventListener('message', function (e) {
    if (e.origin !== location.origin || e.source !== frame.contentWindow) return;
    var d = e.data;
    if (!d || typeof d.type !== 'string') return;
    switch (d.type) {
      case 'gridcraft:ready':
        hookPrint();
        native.appReady();
        break;
      case 'gridcraft:failed':
        native.appFailed(String(d.error || ''));
        break;
      case 'gridcraft:dirty':
        dirty = !!d.dirty;
        native.setDirty(dirty);
        break;
      case 'gridcraft:save':
        if (d.bytes) relay(String(d.name || ''), new Uint8Array(d.bytes), false);
        break;
    }
  });

  // The app prints by loading a PDF blob URL into a hidden iframe, which does nothing in a
  // WebView. Intercept the blob and hand it to the native print dialog instead.
  function hookPrint() {
    try {
      var w = frame.contentWindow;
      if (!w || w.__gcPrintHooked) return;
      var original = w.URL.createObjectURL;
      w.URL.createObjectURL = function (obj) {
        if (obj && obj.type === PDF_MIME && typeof obj.arrayBuffer === 'function') {
          obj.arrayBuffer()
            .then(function (buf) { relay('print.pdf', new Uint8Array(buf), true); })
            .catch(function (err) { console.error('print blob read failed', err); });
        }
        return original.apply(this, arguments);
      };
      w.__gcPrintHooked = true;
    } catch (err) {
      console.error('print hook failed', err);
    }
  }

  function relay(name, bytes, print) {
    queue = queue.then(function () { return send(name, bytes, print); })
      .catch(function (err) { console.error('relay failed', err); });
  }

  async function send(name, bytes, print) {
    var id = print ? native.beginPrint(name) : native.beginSave(name, '');
    if (!id) return;
    try {
      for (var off = 0; off < bytes.length; off += CHUNK_BYTES) {
        if (!native.appendChunk(id, toBase64(bytes.subarray(off, off + CHUNK_BYTES)))) return;
        await new Promise(function (r) { setTimeout(r, 0); });
      }
      native.finishSave(id);
    } catch (err) {
      native.abortSave(id);
      throw err;
    }
  }

  function toBase64(u8) {
    var s = '';
    for (var i = 0; i < u8.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    }
    return btoa(s);
  }

  function canvas() {
    try {
      var doc = frame.contentDocument;
      return doc ? doc.getElementById('gridcraft_canvas') : null;
    } catch (err) {
      return null;
    }
  }

  var KEY_CODES = {
    Backspace: 8, Tab: 9, Enter: 13, Escape: 27, ' ': 32, PageUp: 33, PageDown: 34, End: 35,
    Home: 36, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Delete: 46
  };

  function fireKey(target, key, mods) {
    var w = frame.contentWindow;
    var isLetter = key.length === 1 && /[a-z]/i.test(key);
    var isDigit = key.length === 1 && /[0-9]/.test(key);
    var init = {
      key: isLetter && mods.shiftKey ? key.toUpperCase() : (isLetter ? key.toLowerCase() : key),
      code: isLetter ? 'Key' + key.toUpperCase() : (isDigit ? 'Digit' + key : (key === ' ' ? 'Space' : key)),
      keyCode: isLetter ? key.toUpperCase().charCodeAt(0) : (isDigit ? key.charCodeAt(0) : (KEY_CODES[key] || 0)),
      ctrlKey: !!mods.ctrlKey, shiftKey: !!mods.shiftKey, altKey: !!mods.altKey, metaKey: !!mods.metaKey,
      bubbles: true, cancelable: true
    };
    init.which = init.keyCode;
    target.dispatchEvent(new w.KeyboardEvent('keydown', init));
    target.dispatchEvent(new w.KeyboardEvent('keyup', init));
  }

  // Sends Escape to the app (closes popups/edit mode). Returns the unsaved-changes state.
  window.gcBack = function () {
    var target = canvas();
    if (target) fireKey(target, 'Escape', {});
    return dirty;
  };

  // Pastes text into the focused sheet as if it came from the system clipboard.
  window.gcPaste = function (text) {
    var target = canvas();
    if (!target) return false;
    var w = frame.contentWindow;
    var data = new w.DataTransfer();
    data.setData('text/plain', String(text == null ? '' : text));
    target.focus();
    target.dispatchEvent(new w.ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
    return true;
  };

  // Synthetic key press, e.g. gcKey('ctrl+z'), gcKey('ctrl+shift+z'), gcKey('F2').
  window.gcKey = function (spec) {
    var target = canvas();
    if (!target || !spec) return false;
    var parts = String(spec).split('+');
    var key = parts.pop();
    var mods = {};
    parts.forEach(function (p) { mods[p.toLowerCase().replace(/^(ctrl|control)$/, 'ctrl') + 'Key'] = true; });
    target.focus();
    fireKey(target, key, mods);
    return true;
  };
})();
