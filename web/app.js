(function(){
'use strict';
var P=self.PayNotifyParser,CATS=P.CATEGORIES,KEY='notifyledger.v1';
var $=function(id){return document.getElementById(id);};
var state=null;

function save(){try{localStorage.setItem(KEY,JSON.stringify(state));}catch(e){}}
function load(){try{var r=localStorage.getItem(KEY);if(r)return JSON.parse(r);}catch(e){}return null;}
function uid(){return Date.now().toString(36)+Math.random().toString(36).slice(2,7);}
function pad(n){return(n<10?'0':'')+n;}
function todayStr(){var d=new Date();return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());}
function nowHHMM(){var d=new Date();return pad(d.getHours())+':'+pad(d.getMinutes());}
function fmt(v){return Number(v).toFixed(2);}

var CAT_COLOR={dining:'#E08B3A',shopping:'#3A7CA8',transport:'#236B55',life:'#236B55',other:'#929B94'};
var SRC_COLOR={'微信':'#2ea56b','支付宝':'#2f7cf6','银联':'#f5a623'};

function seed(){
  var t=todayStr();
  state.entries=[
    {id:uid(),date:t,time:'08:12',source:'微信',merchant:'豆浆油条铺',amount:8,category:'dining',raw:'微信支付 扣款成功 ¥8.00 豆浆油条铺 08:12',income:false},
    {id:uid(),date:t,time:'12:30',source:'支付宝',merchant:'美团外卖',amount:15,category:'dining',raw:'支付宝 向美团外卖支付 15.00元',income:false},
    {id:uid(),date:t,time:'12:47',source:'微信',merchant:'XX便利店',amount:23.5,category:'shopping',raw:'微信支付 支付成功 ¥23.50 XX便利店 12:47',income:false}
  ];
}
function initState(){
  state=load();
  if(!state||!Array.isArray(state.entries)){state={entries:[],rules:[],consent:false};seed();save();}
  if(typeof state.consent!=='boolean')state.consent=false;
  if(!Array.isArray(state.rules))state.rules=[];
}

var toastTimer=null;
function toast(msg,type){var el=$('toast');el.textContent=msg;el.className='show '+(type||'ok');clearTimeout(toastTimer);toastTimer=setTimeout(function(){el.className='';},2400);}

/* 导航 */
function goPage(name){
  document.querySelectorAll('.tabbar button').forEach(function(b){b.classList.toggle('active',b.dataset.page===name);});
  document.querySelectorAll('.page').forEach(function(s){s.classList.toggle('active',s.id==='page-'+name);});
  if(name==='bill')renderBill();
  if(name==='rules')renderRules();
  if(name==='stats')renderStats();
  if(name==='book')renderRecent();
  window.scrollTo(0,0);
}

/* 记账 → 解析 → 确认入账 */
var pendingKeeps=[],pendingDiscards=[],confirmIdx=0,editingBillId=null;
function parseToConfirm(text){
  text=(text||'').trim();
  if(!text){toast('请输入或粘贴内容','warn');return;}
  var segs=P.splitTransactions?P.splitTransactions(text):[text];
  var keeps=[],discards=[];
  (segs.length?segs:[text]).forEach(function(seg){
    var v=judgeWithIgnore(seg);
    if(v.verdict==='keep')keeps.push({seg:seg,e:v.entry});
    else discards.push({seg:seg,reason:v.reason});
  });
  if(!keeps.length){toast('未识别到可入账的支付信息：'+(discards[0]?discards[0].reason:'非支付'),'warn');return;}
  pendingKeeps=keeps;pendingDiscards=discards;
  openConfirm(keeps[0],0);
}

function openConfirm(item,idx){
  confirmIdx=idx;editingBillId=null;
  var e=item.e;
  $('sheetTitle').textContent='确认入账';
  $('btnDeleteBill').hidden=true;
  $('btnSheetSave').textContent='确认入账';
  $('shIc').textContent=(e.source||'未').slice(0,1);
  $('shIc').style.background=SRC_COLOR[e.source]||'#aeaeb2';
  $('shName').textContent=e.source||'未知来源';
  $('shTag').textContent='支付成功';
  $('shAmt').textContent='¥'+fmt(e.amount);
  $('shMerchant').value=e.merchant||'';
  $('shSource').value=e.source||'未知';
  $('shTime').value=e.time||nowHHMM();
  $('shNote').value=item.seg||'';
  var cat=e.category||'other';
  curCat.val=cat;
  renderCatPick('catPick',cat);
  var hint='';
  if(e.via==='learned'){var r=state.rules.find(function(x){return x.merchant.toLowerCase()===String(e.merchant||'').trim().toLowerCase();});if(r)hint='已应用学习规则：'+r.merchant+' → '+(CATS[r.category]||r.category);}
  else{var dflt=P.classify(e.merchant,[]);if(dflt.via!=='unknown')hint='默认关键词匹配：'+(CATS[dflt.category]||dflt.category);}
  $('ruleHint').textContent=hint||'未匹配到规则，默认归为「'+(CATS[cat]||'其他')+'」';
  $('ruleHint').className='rule-hint'+(hint?'':' none');
  showSheet('billSheet');
}

var curCat={val:'other'},ruleCat={val:'other'};
function renderCatPick(containerId,current){
  var box=$(containerId);box.innerHTML='';
  Object.keys(CATS).forEach(function(k){
    var c=document.createElement('button');c.type='button';
    c.className='cat-chip'+(k===current?' active':'');c.textContent=CATS[k];
    c.addEventListener('click',function(){
      box.querySelectorAll('.cat-chip').forEach(function(x){x.classList.remove('active');});
      c.classList.add('active');
      if(containerId==='catPick')curCat.val=k;
      else if(containerId==='ruleCatPick')ruleCat.val=k;
    });
    box.appendChild(c);
  });
}
function showSheet(id){$(id).hidden=false;}
function hideSheet(id){$(id).hidden=true;}

function confirmCurrent(){
  var item=pendingKeeps[confirmIdx];if(!item)return;
  var e=item.e;
  state.entries.push({id:uid(),date:todayStr(),time:e.time||nowHHMM(),source:e.source||'未知',
    merchant:e.merchant||'未知商户',amount:e.amount,category:curCat.val||e.category,
    raw:item.seg,income:false,note:$('shNote').value});
  save();
  if(curCat.val&&curCat.val!==(e.category||'other'))state.rules=P.learn(state.entries[state.entries.length-1].merchant,curCat.val,state.rules),save();
  if(confirmIdx+1<pendingKeeps.length){
    toast('已入账 1 笔，还有 '+(pendingKeeps.length-confirmIdx-1)+' 笔待确认','ok');
    openConfirm(pendingKeeps[confirmIdx+1],confirmIdx+1);
  }else{
    hideSheet('billSheet');
    toast('已入账 '+pendingKeeps.length+' 笔'+(pendingDiscards.length?'，丢弃 '+pendingDiscards.length+' 条非支付':''),'ok');
    pendingKeeps=[];pendingDiscards=[];
    goPage('bill');
  }
}


/* 常用分类(记账页快捷入口) */
var _cqIcons={
  dining:'<path d="M7 3v6a2 2 0 0 0 2 2h0a2 2 0 0 0 2-2V3M9 11v10M4 3v4a2 2 0 0 0 2 2" stroke-linecap="round"/>',
  shopping:'<rect x="5" y="8" width="14" height="12" rx="2"/><path d="M8 8V5h8v3" stroke-linecap="round"/>',
  transport:'<circle cx="8" cy="17" r="1.5"/><circle cx="16" cy="17" r="1.5"/><path d="M3 17h2a3 3 0 0 0 3-3V8H3v6z" stroke-linecap="round"/>',
  life:'<path d="M8 3h8l-1 7a3 3 0 0 1-6 0L8 3zM10 14v7M14 14v7" stroke-linejoin="round" stroke-linecap="round"/>',
  other:'<circle cx="12" cy="12" r="8"/><path d="M9 9h6M9 13h6" stroke-linecap="round"/>'
};
var _cqOrder=Object.keys(CATS);
function renderCatQuick(){
  var box=$("catQuick");if(!box)return;
  box.innerHTML="";
  _cqOrder.slice(0,5).forEach(function(k){
    var d=document.createElement("div");d.className="cq";
    var color=(typeof CAT_COLOR!=="undefined"&&CAT_COLOR[k])||"#929B94";
    d.innerHTML='<div class="cqc" style="background:'+color+'1F"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="'+color+'" stroke-width="1.7">'+(_cqIcons[k]||_cqIcons.other)+'</svg></div><b>'+CATS[k]+"</b>";
    d.addEventListener("click",function(){openConfirmQuick(k);});
    box.appendChild(d);
  });
}
function openConfirmQuick(k){
  curCat.val=k;
  goPage("book");
  manualAdd(k);
}

/* 账单 */
var billFilter={q:'',cat:'',src:'',days:30};
// 统一日期过滤: days=0 全部; 数字=N 天; 'month'/'half'/'year' 日历口径
function dateCutoff(days){
  if(!days)return null;
  if(days===0)return null;
  var d=new Date();
  if(days===1){d.setHours(0,0,0,0);return d;}
  if(days<=365){d.setDate(d.getDate()-(days-1));return d;}
  // 半年/1年 按日历月
  if(days===180){var h=new Date();h.setMonth(h.getMonth()-6);return h;}
  if(days===365){var y=new Date();y.setFullYear(y.getFullYear()-1);return y;}
  d.setDate(d.getDate()-days);return d;
}
function inTimeRange(e){
  if(!billFilter.days)return true;
  var cut=dateCutoff(billFilter.days);
  if(!cut)return true;
  var ds=new Date(e.date+'T00:00:00');
  return ds>=cut;
}
function friendlyDate(ds){var today=todayStr();if(ds===today)return'今天';
  var y=new Date();y.setDate(y.getDate()-1);var yd=y.getFullYear()+'-'+pad(y.getMonth()+1)+'-'+pad(y.getDate());
  if(ds===yd)return'昨天';return ds.slice(5).replace('-','/')+' 周'+'日一二三四五六'[new Date(ds).getDay()];}

function renderBill(){
  var ul=$('billGroups');ul.innerHTML='';
  var list=state.entries.slice();
  if(billFilter.q)list=list.filter(function(e){return(e.merchant||'').toLowerCase().indexOf(billFilter.q.toLowerCase())>=0||(e.raw||'').toLowerCase().indexOf(billFilter.q.toLowerCase())>=0;});
  if(billFilter.cat)list=list.filter(function(e){return e.category===billFilter.cat;});
  if(billFilter.src)list=list.filter(function(e){return(e.source||'')===billFilter.src;});
  list=list.filter(inTimeRange);
  var out=list.filter(function(e){return!e.income;}).reduce(function(s,e){return s+e.amount;},0);
  var inc=list.filter(function(e){return e.income;}).reduce(function(s,e){return s+e.amount;},0);
  var sum=$('billSummary');
  var rangeLabel=billFilter.days===1?'今日':(billFilter.days===0?'全部':'近'+billFilter.days+'天');
  if(list.length){sum.hidden=false;var _billCnt=list.filter(function(e){return!e.income;}).length;sum.innerHTML='<div class="bs"><span>'+rangeLabel+'支出</span><b>¥'+fmt(out)+'</b></div><div class="bs cnt"><span>共</span><b>'+_billCnt+' 笔</b></div>';}
  else sum.hidden=true;
  if(!list.length){ul.innerHTML='<div class="empty">没有符合条件的账单</div>';return;}
  list.sort(function(a,b){return(b.date+b.time).localeCompare(a.date+a.time);});
  var groups={};list.forEach(function(e){(groups[e.date]=groups[e.date]||[]).push(e);});
  Object.keys(groups).sort().reverse().forEach(function(date){
    var h=document.createElement('div');h.className='bill-date';h.textContent=friendlyDate(date);ul.appendChild(h);
    groups[date].forEach(function(e){
      var row=document.createElement('div');row.className='bill-row';
      var ic=document.createElement('span');ic.className='b-ic';
      ic.textContent=e.income?'收':(e.source||'未').slice(0,1);
      ic.style.background=e.income?'var(--green)':(SRC_COLOR[e.source]||'#aeaeb2');
      var main=document.createElement('div');main.className='b-main';
      var mb=document.createElement('b');mb.textContent=e.merchant||'未知商户';
      var ms=document.createElement('span');ms.textContent=(e.source?e.source+' · ':'')+(CATS[e.category]||'其他');
      main.appendChild(mb);main.appendChild(ms);
      var right=document.createElement('div');right.className='b-right';
      var rb=document.createElement('b');rb.className=e.income?'in-c':'out';rb.textContent=(e.income?'+':'-')+'¥'+fmt(e.amount);
      var rs=document.createElement('span');rs.textContent=e.time||'';
      right.appendChild(rb);right.appendChild(rs);
      row.appendChild(ic);row.appendChild(main);row.appendChild(right);
      row.addEventListener('click',function(){openEditBill(e);});
      ul.appendChild(row);
    });
  });
}

function openEditBill(e){
  editingBillId=e.id;
  $('sheetTitle').textContent='编辑账单';
  $('btnDeleteBill').hidden=false;
  $('btnSheetSave').textContent='保存修改';
  $('shIc').textContent=(e.source||'未').slice(0,1);
  $('shIc').style.background=SRC_COLOR[e.source]||'#aeaeb2';
  $('shName').textContent=e.source||'未知来源';
  $('shTag').textContent=e.income?'收入':'支出';
  $('shAmt').textContent=(e.income?'+':'')+'¥'+fmt(e.amount);
  $('shMerchant').value=e.merchant||'';
  $('shSource').value=e.source||'未知';
  $('shTime').value=e.time||'';
  $('shNote').value=e.note||e.raw||'';
  curCat.val=e.category||'other';
  renderCatPick('catPick',curCat.val);
  $('ruleHint').textContent='修改分类将自动记住为规则';
  $('ruleHint').className='rule-hint none';
  showSheet('billSheet');
}

function saveBillEdit(){
  if(!editingBillId)return;
  var e=state.entries.find(function(x){return x.id===editingBillId;});
  if(!e)return;
  e.merchant=$('shMerchant').value.trim()||e.merchant;
  e.source=$('shSource').value.trim()||e.source;
  e.time=$('shTime').value||e.time;
  e.category=curCat.val||e.category;
  e.note=$('shNote').value;
  save();hideSheet('billSheet');editingBillId=null;
  renderBill();renderStats();renderRecent();
  toast('已保存修改','ok');
}

function deleteBill(){
  if(!editingBillId)return;
  if(!confirm('确认删除这笔账单？不可恢复。'))return;
  state.entries=state.entries.filter(function(x){return x.id!==editingBillId;});
  save();hideSheet('billSheet');editingBillId=null;
  renderBill();renderStats();renderRecent();
  toast('已删除','ok');
}

function exportCsv(){
  var rows=[['日期','时间','来源','商户','金额','分类','备注','收入']];
  state.entries.slice().sort(function(a,b){return(a.date+a.time).localeCompare(b.date+b.time);}).forEach(function(e){
    rows.push([e.date,e.time,e.source||'',e.merchant||'',fmt(e.amount),CATS[e.category]||e.category,String(e.note||e.raw||''),e.income?'是':'否']);
  });
  var csv=rows.map(function(r){return r.map(function(c){return'"'+String(c).replace(/"/g,'""')+'"';}).join(',');}).join('\n');
  var blob=new Blob(['\ufeff'+csv],{type:'text/csv;charset=utf-8'});
  var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='notifyledger_'+todayStr()+'.csv';a.click();
  toast('已导出 CSV','ok');
}

/* 规则 */
function renderRules(){
  var total=state.rules.length,enabled=state.rules.filter(function(r){return r.enabled!==false;}).length;
  if($('ruleStat'))$('ruleStat').innerHTML='<div class="rs"><b>'+total+'</b><span>总规则</span></div><div class="rs on"><b>'+enabled+'</b><span>已启用</span></div><div class="rs warn"><b>'+(total-enabled)+'</b><span>已停用</span></div>';
  var ul=$('ruleList');ul.innerHTML='';
  if(!state.rules.length){ul.innerHTML='<div class="empty"><div class="em-ic"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="5" y="4" width="14" height="16" rx="2"/><path d="M9 9h6M9 13h4"/></svg></div>还没有分类规则<br>在账单里改一次分类就会自动记住</div>';return;}
  state.rules.forEach(function(r,i){
    var on=r.enabled!==false;
    var li=document.createElement('div');li.className='rule-item';
    var ic=document.createElement('span');ic.className='b-ic';ic.style.background=CAT_COLOR[r.category]||'#aeaeb2';
    ic.textContent=(CATS[r.category]||'其').slice(0,1);
    var m=document.createElement('div');m.className='rm';
    m.innerHTML='<b></b><span></span>';
    if(r.ignore){m.children[0].textContent=r.merchant;m.children[1].textContent='忽略通知 · 不入账';}
    else{m.children[0].textContent=r.merchant+' → '+(CATS[r.category]||r.category);m.children[1].textContent='自动分类';}
    var ed=document.createElement('button');ed.className='link';ed.textContent='编辑';
    ed.addEventListener('click',function(){editRule(i);});
    var sw=document.createElement('label');sw.className='switch';
    var cb=document.createElement('input');cb.type='checkbox';cb.checked=on;
    cb.addEventListener('change',function(){r.enabled=cb.checked;save();renderRules();});
    var i2=document.createElement('i');sw.appendChild(cb);sw.appendChild(i2);
    var del=document.createElement('button');del.className='link';del.textContent='删除';
    del.addEventListener('click',function(){if(confirm('确认删除规则「'+r.merchant+'」？')){state.rules.splice(i,1);save();renderRules();toast('已删除规则：'+r.merchant,'ok');}});
    li.appendChild(ic);li.appendChild(m);li.appendChild(ed);li.appendChild(sw);li.appendChild(del);
    ul.appendChild(li);
  });
}

function newRule(){
  if($('ruleMerchant'))$('ruleMerchant').value='';
  if($('ruleType'))$('ruleType').value='classify';
  if($('ruleCatSel'))$('ruleCatSel').value='other';
  syncRuleTypeUI();
  $('ruleSheetTitle').textContent='新建规则';
  editingRuleIdx=-1;
  showSheet('ruleSheet');
  setTimeout(function(){if($('ruleMerchant'))$('ruleMerchant').focus();},50);
}
var editingRuleIdx=-1;
function syncRuleTypeUI(){
  var t=$('ruleType');if(!t)return;
  var ignore=t.value==='ignore';
  var box=$('ruleCatBox');if(box)box.style.display=ignore?'none':'';
  var hint=$('ruleHint2');if(hint)hint.textContent=ignore?'选择「忽略通知」时，匹配商户将不再自动入账。':'将商户关键词归入所选分类。';
  var save=$('btnRuleSave');if(save)save.textContent=ignore?'保存忽略规则':'保存规则';
}
// 编辑已有规则
function editRule(idx){
  var r=state.rules[idx];if(!r)return;
  editingRuleIdx=idx;
  $('ruleSheetTitle').textContent='编辑规则';
  if($('ruleMerchant'))$('ruleMerchant').value=r.merchant||'';
  if($('ruleType'))$('ruleType').value=r.ignore?'ignore':'classify';
  if($('ruleCatSel'))$('ruleCatSel').value=r.category||'other';
  syncRuleTypeUI();
  showSheet('ruleSheet');
}
function saveRule(){
  var m=$('ruleMerchant').value.trim();
  if(!m){toast('请填写商户关键词','warn');return;}
  var rtype=$('ruleType')?$('ruleType').value:'classify';
  var cat=$('ruleCatSel')?$('ruleCatSel').value:'other';
  var isIgnore=rtype==='ignore';
  // 敏感内容防护: 拒绝保存疑似验证码/卡号/身份证/地址
  if(/(验证码|\d{4,})/.test(m)&&m.length<=8){toast('关键词疑似验证码/编号，已忽略保存','warn');return;}
  if(isIgnore){
    // 忽略规则: 标记 ignore=true, 不参与自动分类入账
    state.rules=P.learn(m,cat,state.rules);
    var r=state.rules.find(function(x){return x.merchant.toLowerCase()===m.toLowerCase();});
    if(r){r.ignore=true;r.enabled=true;}
    save();hideSheet('ruleSheet');renderRules();
    toast('已添加忽略规则：'+m,'ok');
  }else{
    state.rules=P.learn(m,cat,state.rules);
    var r2=state.rules.find(function(x){return x.merchant.toLowerCase()===m.toLowerCase();});
    if(r2){r2.ignore=false;r2.enabled=true;}
    save();hideSheet('ruleSheet');renderRules();
    toast('已保存规则：'+m+' → '+(CATS[cat]||''),'ok');
  }
  editingRuleIdx=-1;
}
// judge 前过滤忽略规则: 包一层
function judgeWithIgnore(seg){
  var mLow=String(seg).toLowerCase();
  var hitIgnore=state.rules.find(function(r){return r.ignore&&r.enabled!==false&&mLow.indexOf((r.merchant||'').toLowerCase())>=0;});
  if(hitIgnore)return{verdict:'discard',reason:'忽略规则：'+hitIgnore.merchant,raw:seg};
  return P.judge(seg,state.rules);
}

/* 分析 */
var statRangeDays=7;
function renderStats(){
  var entries=state.entries;
  function inRange(e){
    var cut=dateCutoff(statRangeDays);
    if(!cut)return true;
    return new Date(e.date+'T00:00:00')>=cut;
  }
  var rel=entries.filter(inRange).filter(function(e){return!e.income;});
  var total=rel.reduce(function(s,e){return s+e.amount;},0);
  var cnt=rel.length;
  var rangeLabel=statRangeDays===1?'今日':(statRangeDays===0?'全部':'近 '+statRangeDays+' 天');

  // 支出概述: 总支出 + 交易笔数 (同一时间范围)
  var cards=$('statCards');
  cards.innerHTML='<div class="sc hl"><span>'+rangeLabel+'支出</span><b>¥'+fmt(total)+'</b></div>'+
    '<div class="sc"><span>交易笔数</span><b>'+cnt+'</b></div>';

  // 分类占比 (环形图) - 来自筛选结果
  var byCat={};rel.forEach(function(e){byCat[e.category]=(byCat[e.category]||0)+e.amount;});
  var catEl=$('statCats');catEl.innerHTML='';
  var donutEl=$('donut');donutEl.innerHTML='';
  var keys=Object.keys(CATS).filter(function(k){return byCat[k];}).sort(function(a,b){return byCat[b]-byCat[a];});
  if(!keys.length){catEl.innerHTML='<div class="empty">该范围内暂无支出</div>';donutEl.innerHTML='<div class="d-center"><b>¥0</b><span>无数据</span></div>';renderTrend([]);return;}
  var r=46,circ=2*Math.PI*r,off=0;
  var svg='<svg viewBox="0 0 120 120" width="120" height="120">';
  svg+='<circle cx="60" cy="60" r="'+r+'" fill="none" stroke="var(--surface2)" stroke-width="16"/>';
  keys.forEach(function(k){var v=byCat[k],frac=v/total,dash=frac*circ;svg+='<circle cx="60" cy="60" r="'+r+'" fill="none" stroke="'+(CAT_COLOR[k]||'#929B94')+'" stroke-width="16" stroke-dasharray="'+dash+' '+(circ-dash)+'" stroke-dashoffset="-'+off+'" transform="rotate(-90 60 60)"/>';off+=dash;});
  svg+='</svg>';
  var topKey=keys[0],topPct=Math.round(byCat[topKey]/total*100);
  donutEl.innerHTML=svg+'<div class="d-center"><b>'+topPct+'%</b><span>'+CATS[topKey]+'</span></div>';
  keys.forEach(function(k){var v=byCat[k],pct=Math.round(v/total*100)||0;
    var lg=document.createElement('div');lg.className='lg';
    lg.innerHTML='<span class="lg-dot" style="background:'+(CAT_COLOR[k]||'#929B94')+'"></span><span class="lg-name">'+CATS[k]+'</span><span class="lg-val">'+pct+'%<span>¥'+fmt(v)+'</span></span>';
    catEl.appendChild(lg);
  });

  // 趋势图 (随时间范围: 短=每日, 长=按月)
  var tt=$('trendTitle');if(tt)tt.textContent=rangeLabel+'趋势';
  renderTrend(rel);
}

/* 趋势图: 按范围决定粒度 (<=31 天=按日, 更长=按月), 堆叠分类色 */
function renderTrend(rel){
  var de=$('statDays');de.innerHTML='';
  var today=todayStr();
  // 计算桶
  var monthly=statRangeDays>31;
  var buckets=[];
  if(monthly){
    // 最近 N 个月
    var now=new Date();
    var n=statRangeDays===180?6:(statRangeDays===365?12:Math.round(statRangeDays/30));
    for(var mi=n;mi>=1;mi--){
      var m=new Date(now.getFullYear(),now.getMonth()-mi,1);
      var key=m.getFullYear()+'-'+pad(m.getMonth()+1);
      var items=rel.filter(function(e){return e.date.slice(0,7)===key;});
      var byCat={};items.forEach(function(e){byCat[e.category]=(byCat[e.category]||0)+e.amount;});
      buckets.push({label:(+key.split('-')[1])+'月',byCat:byCat,total:items.reduce(function(s,e){return s+e.amount;},0),isToday:false});
    }
  }else{
    var n2=statRangeDays||7;
    for(var i=n2-1;i>=0;i--){
      var d=new Date();d.setDate(d.getDate()-i);
      var ds=d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());
      var items=rel.filter(function(e){return e.date===ds;});
      var byCat={};items.forEach(function(e){byCat[e.category]=(byCat[e.category]||0)+e.amount;});
      buckets.push({label:i===0?'今天':ds.slice(5),byCat:byCat,total:items.reduce(function(s,e){return s+e.amount;},0),isToday:i===0});
    }
  }
  var max=Math.max.apply(null,buckets.map(function(b){return b.total;}))||1;
  var allCats=Object.keys(byCatTotals(rel));
  buckets.forEach(function(b){
    var col=document.createElement('div');col.className='dcol';
    var stack=document.createElement('div');stack.className='dstack';
    // 堆叠各分类
    allCats.forEach(function(k){
      var v=b.byCat[k];if(!v)return;
      var h=Math.max(2,Math.round(v/max*100));
      var seg=document.createElement('div');seg.className='dseg';
      seg.style.height=h+'%';seg.style.background=CAT_COLOR[k]||'#929B94';
      seg.title=CATS[k]+' ¥'+fmt(v);
      stack.appendChild(seg);
    });
    var val=document.createElement('div');val.className='dval';val.textContent=b.total?'¥'+fmt(b.total):'';
    var lab=document.createElement('div');lab.className='dlab'+(b.isToday?' today':'');lab.textContent=b.label;
    col.appendChild(val);col.appendChild(stack);col.appendChild(lab);
    de.appendChild(col);
  });
}
function byCatTotals(rel){var o={};rel.forEach(function(e){o[e.category]=(o[e.category]||0)+e.amount;});return o;}

/* 最近一笔 */
function renderRecent(){
  // 记账页最近一笔: 直接内联到 catQuick 下方, 无需独立 recentCard 节点
  var cq=$('catQuick');if(!cq)return;
  var host=cq.parentElement.querySelector('.recent-inline');
  if(!state.entries.length){if(host)host.remove();return;}
  var last=state.entries.slice().sort(function(a,b){return(b.date+b.time).localeCompare(a.date+a.time);})[0];
  if(!host){host=document.createElement('div');host.className='recent-inline card';cq.after(host);}
  host.innerHTML='<h3>最近一笔</h3><div class="ri-row"><b>'+ (last.merchant||'未知商户') +'</b><span>'+(last.source||'')+' · '+(last.time||'')+'</span><em>'+ (last.income?'+':'-') +'¥'+fmt(last.amount) +'</em></div>';
}

/* 自检 */
function runSelfTest(){
  var r=P.selfTest();
  var ok=r.passed===r.total;
  $('selfRunState').textContent=ok?'正常':'有异常';
  $('selfRunState').style.color=ok?'var(--green)':'var(--red)';
  var box=$('selfResult');
  box.innerHTML='<div style="font-weight:700;margin-bottom:6px;color:'+(ok?'var(--green)':'var(--red)')+'">'+r.passed+'/'+r.total+' 通过</div>'+
    r.results.map(function(c){return'<div>'+(c.ok?'✓':'✗ ')+c.name+'</div>';}).join('');
  toast('自检 '+(ok?'全部通过':'有失败项'),'ok');
}

/* 截图 OCR */
var shotFiles=[];
function loadTesseract(){
  return new Promise(function(resolve,reject){
    if(window.Tesseract)return resolve(window.Tesseract);
    var s=document.createElement('script');
    s.src='https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js';
    s.onload=function(){resolve(window.Tesseract);};
    s.onerror=function(){reject(new Error('OCR 库加载失败'));};
    document.head.appendChild(s);
  });
}

/* 初始化 */

function manualAdd(prefCat){
  var amt=parseFloat($('mAmount').value);
  if(!amt||amt<=0){toast('请输入有效金额','warn');return;}
  var m=$('mMerchant').value.trim()||'未命名商户';
  var cat=(prefCat&&CATS[prefCat])?prefCat:($('mCat').value||'other');
  if(prefCat)$('mCat').value=cat;
  var src=$('mSource').value||'现金';
  state.entries.push({id:uid(),date:todayStr(),time:nowHHMM(),source:src,merchant:m,amount:amt,category:cat,raw:'手动录入',income:false,note:''});
  save();
  $('mAmount').value='';$('mMerchant').value='';
  renderRecent();renderBill();renderStats();
  toast('已入账 '+m+' ¥'+fmt(amt),'ok');
  goPage('bill');
}

function init(){renderCatQuick();
  initState();
  document.querySelectorAll('.tabbar button').forEach(function(b){b.addEventListener('click',function(){goPage(b.dataset.page);});});
  $('btnSave').addEventListener('click',manualAdd);
  $('qkParse').addEventListener('click',function(){var c=$('parseCard');var open=c.hidden;c.hidden=!open;$('shotCard').hidden=true;window.scrollTo(0,0);if(!$('parseCard').hidden){setTimeout(function(){$('pushText').focus();},50);if(!$('pushText').value.trim())$('pushText').placeholder='粘贴支付通知，如：微信支付 支付成功 ¥23.50 XX便利店 12:47';}});
  $('qkShot').addEventListener('click',function(){var c=$('shotCard');var open=c.hidden;c.hidden=!open;$('parseCard').hidden=true;window.scrollTo(0,0);if(!$('shotCard').hidden){setTimeout(function(){$('shotFile').click();},50);}});
  $('qkManual').addEventListener('click',function(){window.scrollTo(0,0);setTimeout(function(){$('mAmount').focus();},50);});
// [removed] $('examples').addEventListener('click',function(ev){var li=e
  $('btnParse').addEventListener('click',function(){parseToConfirm($('pushText').value);});
  $('shotFile').addEventListener('change',function(){shotFiles=Array.prototype.slice.call($('shotFile').files||[]);renderPreviews();});
  function renderPreviews(){var box=$('shotPreviews');box.innerHTML='';shotFiles.forEach(function(f){var img=document.createElement('img');img.src=URL.createObjectURL(f);box.appendChild(img);});}
  $('btnOcr').addEventListener('click',async function(){
    if(!shotFiles.length){toast('请先选择截图','warn');return;}
    $('btnOcr').disabled=true;
    try{
      var T=await loadTesseract();
      var keeps=[],discards=[];
      for(var i=0;i<shotFiles.length;i++){
        var dataUrl=await new Promise(function(res,rej){var r=new FileReader();r.onload=function(){res(r.result)};r.onerror=rej;r.readAsDataURL(shotFiles[i]);});
        var out=await T.recognize(dataUrl,'eng+chi_sim');
        var text=P.cleanOcrText(out.data.text);
        if(!text){discards.push({seg:'(无法识别)',reason:'OCR 无结果'});continue;}
        var segs=P.splitTransactions?P.splitTransactions(text):[text];
        segs.forEach(function(seg){var v=P.judge(seg,state.rules);if(v.verdict==='keep')keeps.push({seg:seg,e:v.entry});else discards.push({seg:seg,reason:v.reason});});
      }
      if(!keeps.length)toast('未从截图中识别到支付信息','warn');
      else{pendingKeeps=keeps;pendingDiscards=discards;openConfirm(keeps[0],0);}
    }catch(err){toast('OCR 失败：'+err.message,'warn');}
    finally{$('btnOcr').disabled=false;shotFiles=[];$('shotFile').value='';renderPreviews();}
  });
// [removed] $('btnAllBill').addEventListener('click',function(){goPage('
  $('btnBillTools').addEventListener('click',function(){$('billMenu').hidden=!$('billMenu').hidden;});
  $('btnCsv').addEventListener('click',exportCsv);
  $('billSearch').addEventListener('input',function(){billFilter.q=this.value.trim();renderBill();});
  if($('fTime'))$('fTime').addEventListener('change',function(){billFilter.days=this.value===''?0:parseInt(this.value,10);renderBill();});
  if($('fCat'))$('fCat').addEventListener('change',function(){billFilter.cat=this.value||'';renderBill();});
  if($('fSrc'))$('fSrc').addEventListener('change',function(){billFilter.src=this.value||'';renderBill();});
// [removed] $('btnNewRule').addEventListener('click',newRule);
  if($('ruleAddCard'))$('ruleAddCard').addEventListener('click',newRule);
  if($('ruleType'))$('ruleType').addEventListener('change',syncRuleTypeUI);
  if($('btnRulesPlus'))$('btnRulesPlus').addEventListener('click',newRule);
  if($('ruleMerchant')){var _rm=$('ruleMerchant');_rm.addEventListener('input',function(){});}
  $('btnRuleSave').addEventListener('click',saveRule);
  document.querySelectorAll('#statRange .seg-btn').forEach(function(b){b.addEventListener('click',function(){document.querySelectorAll('#statRange .seg-btn').forEach(function(x){x.classList.remove('active');});b.classList.add('active');statRangeDays=b.dataset.range==='all'?0:parseInt(b.dataset.range,10);renderStats();});});
  if($('siListener'))$('siListener').addEventListener('click',function(){$('listenerSub').textContent='已开启 · 支付通知自动入账';});
  if($('siAi'))$('siAi').addEventListener('click',function(){$('aiSub').textContent='未配置 · 本地规则自动判断';});
  if($('btnSelfRun2'))$('btnSelfRun2').addEventListener('click',runSelfTest);
  $('btnSettings').addEventListener('click',function(){
    var size=new Blob([localStorage.getItem(KEY)||'']).size;
    $('setStorageInfo').textContent=state.entries.length+' 笔 · '+Math.round(size/1024)+' KB';
    $('consentToggle').checked=state.consent;
    showSheet('setSheet');
  });
  $('consentToggle').addEventListener('change',function(){state.consent=this.checked;save();toast(this.checked?'已开启自动识别授权':'已关闭自动识别授权','ok');});
  $('btnSetCsv').addEventListener('click',function(){exportCsv();hideSheet('setSheet');});
  $('btnSetReset').addEventListener('click',function(){if(confirm('清空演示数据？手动记录的账单也会清空。')){var keep=state.rules,consent=state.consent;state={entries:[],rules:keep,consent:consent};seed();save();renderRecent();renderBill();renderRules();renderStats();toast('已重置','ok');}});
  $('btnSelfRun').addEventListener('click',runSelfTest);
  document.querySelectorAll('[data-close]').forEach(function(b){b.addEventListener('click',function(){hideSheet('billSheet');hideSheet('ruleSheet');hideSheet('setSheet');editingBillId=null;pendingKeeps=[];});});
  $('btnSheetSave').addEventListener('click',function(){if(!$('billSheet').hidden){if(editingBillId)saveBillEdit();else confirmCurrent();}});
  $('btnDeleteBill').addEventListener('click',deleteBill);
  renderRecent();renderBill();renderRules();renderStats();
}
document.addEventListener('DOMContentLoaded',function(){init();var p=new URLSearchParams(location.search).get('page');if(p)setTimeout(function(){goPage(p);},1500);});
})();