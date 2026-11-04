/**
 * judge 判断层测试 (对齐 Android JudgeChain: 保留/丢弃/弃权 三态)
 * 运行: node D:/apk2/web/test/judge.test.js  (退出码 0 = 全绿)
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
  check('便利店消费保留', k1.verdict === 'keep' && k1.entry.amountCents === 2350 && k1.entry.merchant === 'XX便利店', JSON.stringify(k1));
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

   console.log('[judge] 无 AI 参数(与 Android 对齐: 纯本地)');
   check('P.judge 签名 arity = 2', P.judge.length === 2, 'arity=' + P.judge.length);

  console.log('[judge] OCR 文本清洗');
  check('多行 OCR 清洗成一行', P.cleanOcrText('微信支付\n支付成功\n¥23.50\nXX便利店 12:47') === '微信支付 支付成功 ¥23.50 XX便利店 12:47');
  check('空 OCR 返回空串', P.cleanOcrText('') === '');

  var total = pass + fail;
  console.log('\n结果: ' + pass + '/' + total + ' 通过' + (fail ? ', 失败: ' + failures.join(', ') : ' ✓'));
  process.exit(fail ? 1 : 0);
})();
