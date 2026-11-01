/**
 * PayNotifyParser — 支付通知解析 / 自动分类 / 规则学习（浏览器 + Node 双端 UMD 模块）
 *
 * 浏览器:  <script src="parser.js"></script> 后使用 self.PayNotifyParser
 * Node:    const P = require('./parser.js')
 *
 * 能力:
 *  - detectSource(text)            识别来源: 微信 / 支付宝 / 银联
 *  - isPayment(text)              判断是否为"支出"类支付通知(收入/退款/非支付 → false)
 *  - parse(text)                  提取 来源/金额/商户/时间, 失败返回 {ok:false, reason}
 *  - classify(merchant, rules)    先查用户学习规则, 再按默认关键词, 否则 "other"
 *  - learn(merchant, cat, rules)  去重 upsert, 返回新数组(纯函数, 不修改入参)
 *  - selfTest()                   内置测试用例, 浏览器"自检"页与 Node 测试共用
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.PayNotifyParser = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ---------------- 分类 ---------------- */

  var CATEGORIES = {
    dining: '餐饮',
    shopping: '购物',
    transport: '交通',
    life: '生活',
    other: '其他'
  };

  var DEFAULT_RULES = {
    dining: {
      label: '餐饮',
      keywords: ['美团', '饿了么', '大众点评', '外卖', '餐厅', '饭店', '火锅', '寿司',
        '咖啡', '瑞幸', '星巴克', '奶茶', '茶饮', '豆浆', '油条', '面馆', '拉面',
        '早餐', '午餐', '晚餐', '零食', '烘焙', '蛋糕']
    },
    shopping: {
      label: '购物',
      keywords: ['便利店', '淘宝', '京东', '拼多多', '超市', '沃尔玛', '永辉', '盒马',
        '苏宁', '得物', '闲鱼', '商场', '商城', '旗舰店', '药房', '药']
    },
    transport: {
      label: '交通',
      keywords: ['滴滴', 'DiDi', '地铁', '高铁', '12306', '航空', '航班', '打车',
        '出租车', '单车', '骑行', '加油', '加油站', '高速', '过路']
    },
    life: {
      label: '生活',
      keywords: ['水电', '电费', '水费', '燃气', '话费', '账单', '移动', '联通', '电信',
        '物业', '宽带', '有线电视', '燃气']
    }
  };

  /* ---------------- 通知识别 ---------------- */

  var SOURCE_PATTERNS = [
    { source: '微信', re: /微信/i },
    { source: '支付宝', re: /支付宝/i },
    { source: '银联', re: /银联|云闪付/i }
  ];

  var PAYMENT_MARKERS = /支付|付款|扣款|消费|缴费|买单|代付|充值|话费|油费|水费|电费|网费|交易|结算|刷卡|订单完成/;
  var INCOME_PATTERNS = /收到|退回|退款|入账|到账/;

  function detectSource(text) {
    for (var i = 0; i < SOURCE_PATTERNS.length; i++) {
      if (SOURCE_PATTERNS[i].re.test(text)) return SOURCE_PATTERNS[i].source;
    }
    return null;
  }

  /** 是否为"支出"支付通知; 收入/退款/非支付一律 false */
  function isPayment(text) {
    if (!text || typeof text !== 'string') return false;
    if (INCOME_PATTERNS.test(text)) return false;
    return PAYMENT_MARKERS.test(text);
  }

  /* ---------------- 字段提取 ---------------- */

  var AMOUNT_RE = /¥\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)|(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*元/g;
  var DISCOUNT_CTX_RE = /(优惠|立减|减免|抵扣)\s*$/;

  function extractAmount(text) {
    if (!text) return null;
    AMOUNT_RE.lastIndex = 0;
    var m;
    var found = [];
    while ((m = AMOUNT_RE.exec(text)) !== null) {
      var raw = m[1] != null ? m[1] : m[2];
      var v = parseFloat(raw.replace(/,/g, ''));
      if (!isFinite(v) || v <= 0) continue;
      var dec = (raw.split('.')[1] || '').length;
      // 前缀出现"优惠/立减/减免/抵扣"的金额视为优惠额, 不作为实付金额
      var discount = DISCOUNT_CTX_RE.test(text.slice(Math.max(0, m.index - 4), m.index));
      found.push({ value: Math.round(v * 100) / 100, dec: dec, discount: discount });
    }
    if (!found.length) return null;
    var main = found.filter(function (f) { return !f.discount; });
    if (!main.length) main = found;
    // 优先"两位小数"的金额(真实支付金额), 其次金额大者
    main.sort(function (a, b) { return (b.dec - a.dec) || (b.value - a.value); });
    return main[0].value;
  }

  var TIME_RE = /\d{1,2}:\d{2}(?::\d{2})?/;
  var DATE_RE = /\d{4}[-\/]\d{1,2}[-\/]\d{1,2}/;

  function extractTime(text) {
    if (!text) return null;
    var m = text.match(TIME_RE) || text.match(DATE_RE);
    return m ? m[0] : null;
  }

  function extractMerchant(text) {
    if (!text) return null;
    var t = text;
    // 1) 去来源名
    t = t.replace(/微信\s*支付|微信|支付宝|银联\s*支付|银联|云闪付/g, '');
    // 2) 去金额(¥xx / xx元)
    t = t.replace(/¥\s*\d+(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:,\d{3})*(?:\.\d{1,2})?\s*元/g, '');
    // 3) 去状态词
    t = t.replace(/支付成功|交易成功|消费成功|扣款成功|退款成功|订单完成|刷卡成功|付款|扣款|车费|支付/g, '');
    // 4) 去收入句式
    t = t.replace(/收到(一个|好友)? ?转账|收款/g, '');
    // 5) 去日期时间
    t = t.replace(/\d{4}[-\/]\d{1,2}[-\/]\d{1,2}/g, '');
    t = t.replace(/\d{1,2}:\d{2}(?::\d{2})?/g, '');
    // 6) 去连接/说明词
    t = t.replace(/向|给|商户|本笔|备注|您|已/g, '');
    // 7) 清理标点
    t = t.replace(/[，。|、:：,\s]/g, ' ').trim();
    // 8) 折叠连续空白(与 Kotlin 侧 extractMerchant 末步 1:1 对齐)
    t = t.replace(/\s{2,}/g, ' ').trim();
    return t || null;
  }

  /** 解析一条通知文本。成功 {ok:true, source, amount, merchant, time, raw}; 失败 {ok:false, reason} */
  function parse(text) {
    if (!text || typeof text !== 'string' || !text.trim()) {
      return { ok: false, reason: '空输入' };
    }
    if (INCOME_PATTERNS.test(text)) {
      return { ok: false, reason: '收入/退款类通知, 不计入支出账本' };
    }
    var hasPaymentMarker = PAYMENT_MARKERS.test(text);
    var amount = extractAmount(text);
    var merchant = extractMerchant(text);
    // 没有支付动作词时, 若同时有 金额+商户名, 仍视为支付(宽松版, 覆盖"美团外卖 15元"这种)
    if (!hasPaymentMarker && !(amount != null && merchant)) {
      return { ok: false, reason: '非支付通知, 已丢弃' };
    }
    if (amount == null) {
      return { ok: false, reason: '未识别到金额' };
    }
    return {
      ok: true,
      source: detectSource(text),
      amount: amount,
      merchant: extractMerchant(text),
      time: extractTime(text),
      raw: text
    };
  }

  /* ---------------- 分类 + 规则学习 ---------------- */

  /** 先匹配用户学习规则(精确商户名, 忽略大小写), 再默认关键词, 否则 other */
  function classify(merchant, learnedRules) {
    var name = (merchant || '').trim();
    if (name) {
      if (learnedRules && learnedRules.length) {
        var low = name.toLowerCase();
        for (var i = 0; i < learnedRules.length; i++) {
          if (String(learnedRules[i].merchant).trim().toLowerCase() === low) {
            return { category: learnedRules[i].category, via: 'learned' };
          }
        }
      }
      for (var key in DEFAULT_RULES) {
        var kws = DEFAULT_RULES[key].keywords;
        for (var j = 0; j < kws.length; j++) {
          if (name.indexOf(kws[j]) !== -1) {
            return { category: key, via: 'default' };
          }
        }
      }
    }
    return { category: 'other', via: 'unknown' };
  }

  /** upsert 学习规则(纯函数, 返回新数组); 分类非法时原样返回 */
  function learn(merchant, category, rules) {
    var arr = rules || [];
    var name = String(merchant || '').trim();
    if (!name || !CATEGORIES[category]) return arr;
    var low = name.toLowerCase();
    var out = arr.filter(function (r) {
      return String(r.merchant).trim().toLowerCase() !== low;
    });
    out.push({ merchant: name, category: category });
    return out;
  }

  var addRule = learn; // 别名

  /* ---------------- 内置自检 ---------------- */

  function selfTest() {
    var cases = [
      { name: '微信消费(便利店)',
        text: '微信支付 支付成功 ¥23.50 XX便利店 12:47',
        expect: { ok: true, source: '微信', amount: 23.5, merchant: 'XX便利店', time: '12:47', category: 'shopping' } },
      { name: '支付宝消费(美团外卖)',
        text: '支付宝 向美团外卖支付 15.00元',
        expect: { ok: true, source: '支付宝', amount: 15, merchant: '美团外卖', category: 'dining' } },
      { name: '收入转账应丢弃',
        text: '微信 收到好友转账 ¥100.00',
        expect: { ok: false } },
      { name: '非支付通知应丢弃',
        text: '系统更新完成, 请重启手机安装最新功能',
        expect: { ok: false } },
      { name: '生活缴费(话费账单)',
        text: '中国移动 话费账单 支付成功 29.00元',
        expect: { ok: true, amount: 29, merchant: '中国移动 话费账单', category: 'life' } },
      { name: '交通(滴滴打车)',
        text: '滴滴出行 订单完成 车费 12.50元 2026-10-20 08:05',
        expect: { ok: true, amount: 12.5, merchant: '滴滴出行', time: '08:05', category: 'transport' } },
      { name: '缺金额应丢弃',
        text: '支付宝 向 XX商户 支付成功',
        expect: { ok: false } },
      { name: '未知商户归"其他"',
        text: '支付宝 向 小明文具 支付 18.00元',
        expect: { ok: true, amount: 18, merchant: '小明文具', category: 'other' } },
      { name: '学习规则优先于默认',
        text: '支付宝 向美团外卖支付 15.00元',
        learned: [{ merchant: '美团外卖', category: 'shopping' }],
        expect: { ok: true, merchant: '美团外卖', category: 'shopping' } }
    ];

    var results = cases.map(function (c) {
      var p = parse(c.text);
      var cl = classify(p.ok ? p.merchant : null, c.learned || []);
      var fail = [];
      function chk(cond, label) { if (!cond) fail.push(label); }
      chk(p.ok === c.expect.ok, '入账=' + p.ok + '(期望' + c.expect.ok + ')');
      if (c.expect.ok) {
        if (c.expect.source != null) chk(p.source === c.expect.source, '来源=' + p.source);
        if (c.expect.amount != null) chk(p.amount === c.expect.amount, '金额=' + p.amount);
        if (c.expect.merchant != null) chk(p.merchant === c.expect.merchant, '商户=' + p.merchant);
        if (c.expect.time != null) chk(p.time === c.expect.time, '时间=' + p.time);
        chk(cl.category === c.expect.category, '分类=' + (CATEGORIES[cl.category] || cl.category) +
          '(期望' + (CATEGORIES[c.expect.category] || c.expect.category) + ')');
      }
      return {
        name: c.name,
        ok: fail.length === 0,
        detail: fail.length === 0 ? '通过' : fail.join('; ')
      };
    });

    return {
      total: results.length,
      passed: results.filter(function (r) { return r.ok; }).length,
      results: results
    };
  }



  /**
   * 拆分一段"多交易混合"文本(比如一张多平台截图 OCR 后的整行) 为独立交易段。
   * 规则: 每行/每段只要同时包含 金额 + 商户或状态词, 就视为一笔独立交易。
   * 返回字符串数组, 逐条走 judge。
   */
  function splitTransactions(text) {
    if (!text || typeof text !== 'string') return [];
    // 以换行、或连续两个"金额+尾随词"之间的空格为界切分。
    // 简化: 按每个"金额"的位置切，段边界=金额起始位置，这样每个金额落一段。
    var parts = [];
    var last = 0;
    var amtRe = /¥\s*\d+(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:,\d{3})*(?:\.\d{1,2})?\s*元/g;
    var m;
    while ((m = amtRe.exec(text)) !== null) {
      if (m.index > last) {
        var seg = text.slice(last, m.index + m[0].length);
        parts.push(seg);
      } else {
        // 前面已有片段(比如连续金额), 合并到上一段
        if (parts.length) parts[parts.length-1] += ' ' + text.slice(last, m.index + m[0].length);
        else parts.push(text.slice(last, m.index + m[0].length));
      }
      last = m.index + m[0].length;
    }
    // 末尾残留
    if (last < text.length) parts.push(text.slice(last));
    // 过滤: 只有包含 金额+ 商户/来源/状态词 的才作为独立交易段, 其余丢弃
    return parts.filter(function (seg) {
      var s = seg.trim();
      return s && (s.match(amtRe) || s.indexOf('¥') !== -1);
    });
  }

    /* ---------------- 判断层 (对齐 Android JudgeChain: 保留/丢弃/弃权 三态) ---------------- */

  // 本地规则判断器是"定论"判断器: 能给出 keep 或 discard, 不会 abstain。
  function ruleJudge(text, learnedRules) {
    var p = parse(text);
    if (!p.ok) return { verdict: 'discard', reason: p.reason, raw: text };
    var cr = classify(p.merchant, learnedRules || []);
    return {
      verdict: 'keep',
      entry: {
        source: p.source, amount: p.amount, merchant: p.merchant,
        time: p.time, raw: text, category: cr.category, via: cr.via
      }
    };
  }

  /**
   * 判断链入口: 本地规则判断 —— 解析成功且关键字门通过才 keep, 否则 discard。
   * 不外呼任何 AI/网络。与 Android PayNotifyParser.judge 对齐。
   */
  function judge(text, learnedRules) {
    return ruleJudge(text, learnedRules);
  }

  /** 本地 OCR 文本清洗: 把截图识别出的零散多行文字清洗成一行, 便于判断 */
  function cleanOcrText(ocrText) {
    if (!ocrText || typeof ocrText !== 'string') return '';
    var lines = ocrText.split(/\r?\n/).map(function (l) { return l.replace(/\t+/g, ' ').trim(); }).filter(function (l) { return l.length > 0; });
    return lines.join(' ');
  }

  return {
    CATEGORIES: CATEGORIES,
    DEFAULT_RULES: DEFAULT_RULES,
    detectSource: detectSource,
    isPayment: isPayment,
    parse: parse,
    extractAmount: extractAmount,
    extractTime: extractTime,
    extractMerchant: extractMerchant,
    classify: classify,
    learn: learn,
    addRule: addRule,
    selfTest: selfTest,
    judge: judge,
    ruleJudge: ruleJudge,
    cleanOcrText: cleanOcrText,
    splitTransactions: splitTransactions
  };
}));
