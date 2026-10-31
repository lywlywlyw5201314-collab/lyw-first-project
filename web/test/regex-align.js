/**
 * 正则 1:1 对齐校验: web/parser.js ↔ Android PayNotifyParser.kt
 *
 * 原理:
 *  1) 从 parser.js 提取所有正则字面量(字符串外扫描, 处理 / 在字符类中的 \/ 转义),
 *     去掉标志(g 无等价、i ↔ IGNORE_CASE), 记录 (pattern, flags)
 *  2) 从 PayNotifyParser.kt 提取所有 Regex("...") 字符串字面量,
 *     反转义 (\\ → \, \" → "), 记录 (pattern, 是否带 RegexOption.IGNORE_CASE)
 *  3) 归一化两侧 pattern(转义/空白), 做集合比较
 *
 * 运行: node D:\apk2\web\test\regex-align.js
 * 全部一致 → 退出码 0; 有差异 → 打印差异并以退出码 1 结束
 */
'use strict';
var fs = require('fs');
var path = require('path');

var JS_PATH = path.join(__dirname, '..', 'parser.js');
var KT_PATH = 'D:\\apk2\\app\\app\\src\\main\\java\\com\\example\\notifyledger\\parser\\PayNotifyParser.kt';

/* ---------- JS 侧: 提取正则字面量 ---------- */
function extractJsRegexes(src) {
  var out = [];
  var i = 0, n = src.length;
  function lastSig(j) {
    // 找到位置 j 之前最近一个"有意义"的字符(跳过空白/行注释起点)
    while (j > 0 && /\s/.test(src.charAt(j - 1))) j--;
    return j > 0 ? src.charAt(j - 1) : '';
  }
  while (i < n) {
    var c = src.charAt(i);
    // 注释
    if (c === '/' && src.charAt(i + 1) === '/') {
      var e = src.indexOf('\n', i);
      i = (e === -1) ? n : e;
      continue;
    }
    if (c === '/' && src.charAt(i + 1) === '*') {
      var e2 = src.indexOf('*/', i + 2);
      i = (e2 === -1) ? n : e2 + 2;
      continue;
    }
    // 字符串
    if (c === "'" || c === '"' || c === '`') {
      var q = c, j = i + 1;
      while (j < n && src.charAt(j) !== q) {
        if (src.charAt(j) === '\\') j++;
        if (c !== '`' && src.charAt(j) === '\n') break;
        j++;
      }
      i = j + 1;
      continue;
    }
    // 正则字面量: 仅当 / 前的有意义字符是 =(,:[;!&|?+ 或行首
    if (c === '/') {
      var prev = lastSig(i);
      if (prev === '' || '(=,:[;!&|?+'.indexOf(prev) !== -1) {
        var k = i + 1, body = '';
        var valid = true;
        while (k < n) {
          var ch = src.charAt(k);
          if (ch === '\\') {
            body += ch + (src.charAt(k + 1) || '');
            k += 2;
            continue;
          }
          if (ch === '/') {
            // 读标志
            var m = k + 1, flags = '';
            while (m < n && /[a-z]/.test(src.charAt(m))) { flags += src.charAt(m); m++; }
            // 反转义 \/ → /
            out.push({ body: body.replace(/\\\//g, '/'), flags: flags, line: src.slice(0, i).split('\n').length });
            i = m;
            valid = true;
            break;
          }
          if (ch === '\n') { valid = false; break; }
          body += ch;
          k++;
        }
        if (!valid) { i++; continue; }
        continue;
      }
    }
    i++;
  }
  return out;
}

/* ---------- Kotlin 侧: 提取 Regex("...") ---------- */
function extractKotlinRegexes(src) {
  var out = [];
  var re = /Regex\(\s*"((?:[^"\\]|\\.)*)"\s*(,\s*(RegexOption\.IGNORE_CASE|regexOptions\([^)]*\)))?\)/g;
  var m;
  while ((m = re.exec(src)) !== null) {
    var s = m[1]
      .replace(/\\\\/g, '\u0000') // 占位
      .replace(/\\(.)/g, '$1')      // Kotlin 字符串字面量转义 → 字符
      .replace(/\u0000/g, '\\');
    var hasIgnore = src.slice(m.index, m.index + m[0].length).indexOf('IGNORE_CASE') !== -1;
    // Kotlin 字符串字面量中 \s \d 等写作 "\\s": 上面的替换把 "\\s" → "\s", 正确
    out.push({ body: s, ignore: hasIgnore, line: src.slice(0, m.index).split('\n').length });
  }
  return out;
}

/* ---------- 归一化: 去掉无意义空白差异 ---------- */
function normalize(body) {
  return body
    .replace(/\s+/g, ' ')
    .trim();
}

function toSet(list) {
  var map = {};
  list.forEach(function (r) {
    var key = normalize(r.body);
    (map[key] = map[key] || []).push(r);
  });
  return map;
}

var jsSrc = fs.readFileSync(JS_PATH, 'utf8');
var ktSrc = fs.readFileSync(KT_PATH, 'utf8');

// JS 侧去掉 g 标志, i 标志保留
var jsRe = extractJsRegexes(jsSrc).map(function (r) {
  return { body: r.body, flags: r.flags.replace(/g/g, ''), line: r.line };
});
var ktRe = extractKotlinRegexes(ktSrc);
// Kotlin 侧把 ignore 标志折进 body 描述
ktRe.forEach(function (r) { r.flags = r.ignore ? 'i' : ''; });

var jsMap = toSet(jsRe);
var ktMap = toSet(ktRe);

var jsKeys = Object.keys(jsMap);
var ktKeys = Object.keys(ktMap);

var missingInKt = jsKeys.filter(function (k) { return !ktMap[k]; });
var missingInJs = ktKeys.filter(function (k) { return !jsMap[k]; });

/* 已知结构等价白名单: JS 正则字面量 ↔ Kotlin 字符串级等价的写法
 *  (JS String.replace(/,/g,'') 等价于 Kotlin String.replace(",",""), 后者非正则) */
var KNOWN_EQUIV = [
  { jsPattern: ',', ktMarker: 'replace(",", "")', note: 'JS /,/g ↔ Kotlin String.replace(",", "")(全部替换, 等价)' },
  { jsPattern: '\\r?\\n', ktMarker: "split('\\n', '\\r')", note: 'JS split(/\\r?\\n/) ↔ Kotlin split(\'\\n\', \'\\r\')(分行语义等价, 两侧随后都过滤空行)' }
];
function knownEquiv(k) {
  return KNOWN_EQUIV.find(function (e) {
    return e.jsPattern === k && ktSrc.indexOf(e.ktMarker) !== -1;
  });
}
missingInKt = missingInKt.filter(function (k) {
  var e = knownEquiv(k);
  if (e) console.log('[已知等价] ' + e.note);
  return !e;
});

// 标志一致性(仅在两侧都存在的 pattern 上检查)
var flagMismatch = [];
Object.keys(jsMap).forEach(function (k) {
  if (!ktMap[k]) return;
  jsMap[k].forEach(function (j) {
    ktMap[k].forEach(function (t) {
      if (j.flags !== t.flags) {
        flagMismatch.push('JS L' + j.line + ' [' + k + '] flags="' + j.flags + '" vs KT L' + t.line + ' flags="' + t.flags + '"');
      }
    });
  });
});

console.log('JS 正则字面量: ' + jsRe.length + ' 个, 去重 ' + jsKeys.length + ' 个 pattern');
console.log('Kotlin Regex("..."): ' + ktRe.length + ' 个, 去重 ' + ktKeys.length + ' 个 pattern');

if (missingInKt.length || missingInJs.length || flagMismatch.length) {
  console.log('\n[差异] Kotlin 侧缺失(JS 有):');
  missingInKt.forEach(function (k) { console.log('  - ' + k); });
  console.log('[差异] JS 侧缺失(Kotlin 有):');
  missingInJs.forEach(function (k) { console.log('  + ' + k); });
  flagMismatch.forEach(function (s) { console.log('[标志不一致] ' + s); });
  console.log('\n结果: 不一致 ✗');
  process.exit(1);
} else {
  // 数量对账: JS 出现次数 vs Kotlin 出现次数(已知等价 pattern 除外, 其 Kotlin 侧无 Regex)
  var countDiff = [];
  jsKeys.forEach(function (k) {
    if (!ktMap[k]) return; // 已知等价或已在上文报过缺失
    if (jsMap[k].length !== ktMap[k].length) {
      countDiff.push(k + ' (JS×' + jsMap[k].length + ' vs KT×' + ktMap[k].length + ')');
    }
  });
  if (countDiff.length) {
    console.log('\n[出现次数差异] ' + countDiff.join('; '));
    console.log('结果: 不完全一致 ✗');
    process.exit(1);
  }
  console.log('\n结果: 正则 1:1 对齐 ✓ (' + jsKeys.length + ' 个 pattern 全部一致, 标志一致)');
}
