/**
 * judge 判断层测试 (对齐 Android JudgeChain: 保留/丢弃/弃权 三态)
 * 运行: node D:/apk/web/test/judge.test.js  (退出码 0 = 全绿)
 */
(function () {
  'use strict';
  var P = require('../parser.js');
  var pass = 0, fail = 0, failures = [];

  function check(label, cond, detail) {
    if (cond) { pass++; console.log('  ✓ ' + label); }
    else { fail++; failures.push(label); console.log('  ✗ ' + label + (detail ? '  → ' + detail : '')); }
  }

  console.log('[judge] 应保留(keep)的支付通知');
  var k1 = P.judge('微信支付 支付成功 ¥23.50 XX便利店 12:47', []);
  check('便利店消费保留', k1.verdict === 'keep' && k1.entry.amount === 23.5 && k1.entry.merchant === 'XX便利店', JSON.stringify(k1));
  var k2 = P.judge('滴滴出行 订单完成 车费 12.50元 2026-10-20 08:05', []);
  check('滴滴打车保留(交通)', k2.verdict === 'keep' && k2.entry.category === 'transport', JSON.stringify(k2.entry));
  var k3 = P.judge('中国移动 话费账单 支付成功 29.00元', []);
  check('话费账单保留(生活)', k3.verdict === 'keep' && k3.entry.category === 'life', JSON.stringify(k3.entry));

  console.log('[judge] 应丢弃(discard)的场景');
  check('收入转账丢弃', P.judge('微信 收到好友转账 ¥100.00', []).verdict === 'discard');
  check('退款丢弃', P.judge('退款成功 ¥5.00', []).verdict === 'discard');
  check('非支付(系统更新)丢弃', P.judge('系统更新完成, 请重启', []).verdict === 'discard');
  check('非支付(语音通话)丢弃', P.judge('微信语音通话 00:12', []).verdict === 'discard');
  check('缺金额丢弃', P.judge('支付宝 向 XX商户 支付成功', []).verdict === 'discard');
  check('空输入丢弃', P.judge('', []).verdict === 'discard');

  console.log('[judge] 学习规则优先');
  var kl = P.judge('支付宝 向美团外卖支付 15.00元', [{ merchant: '美团外卖', category: 'shopping' }]);
  check('学习规则命中(外卖→购物)', kl.verdict === 'keep' && kl.entry.category === 'shopping' && kl.entry.via === 'learned', JSON.stringify(kl.entry));

  console.log('[judge] AI 弃权 → 规则兜底');
  var withAbstain = P.judge('微信支付 支付成功 ¥23.50 XX便利店 12:47', [], function () { return { verdict: 'abstain', reason: 'AI 未配置' }; });
  check('AI 弃权时回落到规则(仍保留)', withAbstain.verdict === 'keep' && withAbstain.entry.amount === 23.5, JSON.stringify(withAbstain));

  console.log('[judge] AI 定论丢弃 → 不再兜底');
  var withAiDiscard = P.judge('随便一段含 支付 的字 23.00元', [], function () { return { verdict: 'discard', reason: 'AI: 不是真实支出' }; });
  check('AI 定论丢弃直接生效', withAiDiscard.verdict === 'discard' && withAiDiscard.reason === 'AI: 不是真实支出', JSON.stringify(withAiDiscard));

  console.log('[judge] 异常 AI → 不崩溃, 回落规则');
  var withThrow = P.judge('微信支付 支付成功 ¥23.50 XX便利店', [], function () { throw new Error('boom'); });
  check('AI 抛异常仍保留(规则兜底)', withThrow.verdict === 'keep', JSON.stringify(withThrow));

  console.log('[judge] OCR 文本清洗');
  check('多行 OCR 清洗成一行', P.cleanOcrText('微信支付\n支付成功\n¥23.50\nXX便利店 12:47') === '微信支付 支付成功 ¥23.50 XX便利店 12:47');
  check('空 OCR 返回空串', P.cleanOcrText('') === '');

  var total = pass + fail;
  console.log('\n结果: ' + pass + '/' + total + ' 通过' + (fail ? ', 失败: ' + failures.join(', ') : ' ✓'));
  process.exit(fail ? 1 : 0);
})();
