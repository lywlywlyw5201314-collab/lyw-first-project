/**
 * parser.js 单元测试（Node 端）
 * 运行: node D:\apk2\web\test\parser.test.js
 * 通过 → 退出码 0；失败 → 打印失败项并以退出码 1 结束
 */
'use strict';
var P = require('../parser.js');
var assert = require('assert');

function report(step, fn) {
  try {
    fn();
    console.log('  \u2713 ' + step);
  } catch (e) {
    console.log('  \u2717 ' + step + '\n      ' + e.message);
    process.exitCode = 1;
  }
}

/* ---------- 1) 内置自检 9 例 ---------- */
var r = P.selfTest();
console.log('\n[1] 内置 selfTest() ' + r.passed + '/' + r.total + ' 通过');
r.results.forEach(function (c) {
  report(c.name, function () {
    assert.ok(c.ok, c.detail);
  });
});

/* ---------- 2) 关键字段精确断言 ---------- */
console.log('\n[2] 字段级断言');
report('微信消费: 金额/商户/时间/来源', function () {
  var p = P.parse('微信支付 支付成功 ¥23.50 XX便利店 12:47');
  assert.strictEqual(p.ok, true);
  assert.strictEqual(p.source, '微信');
  assert.strictEqual(p.amount, 23.5);
  assert.strictEqual(p.merchant, 'XX便利店');
  assert.strictEqual(p.time, '12:47');
});
report('微信支付成功 不留成功前缀到商户', function () {
  var p = P.parse('微信支付成功 ¥12.30 瑞幸咖啡 13:05');
  assert.strictEqual(p.ok, true);
  assert.strictEqual(p.merchant, '瑞幸咖啡');
  assert.strictEqual(p.amount, 12.3);
});
report('便利店 → 购物', function () {
  assert.strictEqual(P.classify('XX便利店', []).category, 'shopping');
});
report('美团外卖 → 餐饮(默认)', function () {
  assert.strictEqual(P.classify('美团外卖', []).category, 'dining');
});
report('学习规则覆盖默认(外卖改购物)', function () {
  var rules = P.learn('美团外卖', 'shopping', []);
  assert.strictEqual(P.classify('美团外卖', rules).category, 'shopping');
  assert.strictEqual(P.classify('美团外卖', rules).via, 'learned');
});
report('learn 为 upsert: 同名商户只保留一条', function () {
  var rules = P.learn('美团外卖', 'shopping', []);
  rules = P.learn('美团外卖', 'dining', rules);
  assert.strictEqual(rules.length, 1);
  assert.strictEqual(rules[0].category, 'dining');
});
report('learn 不修改原数组(纯函数)', function () {
  var before = [{ merchant: 'a', category: 'dining' }];
  var after = P.learn('b', 'shopping', before);
  assert.strictEqual(before.length, 1);
  assert.strictEqual(after.length, 2);
});
report('learn 非法分类忽略', function () {
  var rules = P.learn('x', '不存在的分类', []);
  assert.strictEqual(rules.length, 0);
});
report('商户名匹配忽略大小写', function () {
  var rules = P.learn('DI DI', 'transport', []);
  assert.strictEqual(P.classify('di di', rules).via, 'learned');
});

/* ---------- 3) 丢弃路径 ---------- */
console.log('\n[3] 应丢弃的场景');
report('收入: 收到好友转账', function () {
  assert.strictEqual(P.parse('微信 收到好友转账 ¥100.00').ok, false);
});
report('退款', function () {
  assert.strictEqual(P.parse('支付宝 退款 15.00元').ok, false);
});
report('非支付: 系统更新', function () {
  assert.strictEqual(P.parse('系统更新完成').ok, false);
});
report('非支付: 微信语音通话', function () {
  assert.strictEqual(P.parse('微信 语音通话 结束').ok, false);
});
report('缺金额', function () {
  var p = P.parse('支付宝 支付成功 XX商户');
  assert.strictEqual(p.ok, false);
  assert.strictEqual(p.reason, '未识别到金额');
});
report('空输入', function () {
  assert.strictEqual(P.parse('').ok, false);
  assert.strictEqual(P.parse(null).ok, false);
});

/* ---------- 4) 金额与时间提取 ---------- */
console.log('\n[4] 金额/时间提取');
report('优惠金额不作为实付金额', function () {
  var p = P.parse('支付宝 向 A店 支付 99元 优惠 9.90元');
  assert.strictEqual(p.ok, true);
  assert.strictEqual(p.amount, 99);
});
report('带千分位', function () {
  assert.strictEqual(P.extractAmount('¥1,234.56'), 1234.56);
});
report('无金额返回 null', function () {
  assert.strictEqual(P.extractAmount('今天消费愉快'), null);
});
report('时间: HH:MM', function () {
  assert.strictEqual(P.extractTime('12:47'), '12:47');
});
report('时间: 完整日期', function () {
  assert.strictEqual(P.extractTime('2026-10-20 订单完成'), '2026-10-20');
});
report('无时间返回 null', function () {
  assert.strictEqual(P.extractTime('今天消费'), null);
});

/* ---------- 5) 来源识别 ---------- */
console.log('\n[5] 来源识别');
report('微信', function () { assert.strictEqual(P.detectSource('微信支付成功'), '微信'); });
report('支付宝', function () { assert.strictEqual(P.detectSource('支付宝到账'), '支付宝'); });
report('银联', function () { assert.strictEqual(P.detectSource('银联 扣款成功 5.00元'), '银联'); });
report('未知来源', function () { assert.strictEqual(P.detectSource('滴滴出行 订单完成 12.50元'), null); });

console.log(process.exitCode ? '\n结果: 有失败用例(见上)' : '\n结果: 全部通过 ✓');
