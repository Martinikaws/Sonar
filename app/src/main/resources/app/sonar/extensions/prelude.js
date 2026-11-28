(function (global, info, locale, log) {
  'use strict';
  var status;
  var stringify = JSON.stringify;
  var define = Object.defineProperty;
  var empty = Object.create;
  var slice = Function.prototype.call.bind(String.prototype.slice);
  var fixed = function (target, key, value) {
    define(target, key, { value: value, writable: false, configurable: false });
  };
  var refused = function Function() { throw new Error('Code from strings is unavailable'); };

  fixed(global, 'myspoti', Object.freeze({
    apiVersion: 1,
    extension: Object.freeze(info),
    locale: locale,
    log: log,
    provide: function (hook, fn) {
      if (hook !== 'status' || typeof fn !== 'function') throw new TypeError('Only the status hook is available');
      status = fn;
    }
  }));
  fixed(global, 'console', Object.freeze({ log: log, info: log, warn: log, error: log }));

  // Preserve instanceof Function while disabling compilation from strings.
  refused.prototype = Function.prototype;
  // Rhino's generator prototype is not always a plain object; the Function guards below still apply.
  try { fixed(Object.getPrototypeOf(function* () {}), 'constructor', refused); } catch (ignored) {}
  fixed(Function.prototype, 'constructor', refused);
  fixed(global, 'Function', refused);
  fixed(global, 'eval', refused);

  delete global.__extensionInfo;
  delete global.__locale;
  delete global.__log;
  return function () {
    if (!status) return null;
    var result = status({});
    if (result == null || typeof result.live !== 'boolean') return null;
    var detail = result.detail;
    var output = empty(null);
    output.live = result.live;
    if (typeof detail === 'string') output.detail = slice(detail, 0, 512);
    return stringify(output);
  };
})(this, __extensionInfo, __locale, __log);
