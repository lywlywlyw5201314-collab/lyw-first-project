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

var CAT_COLOR={dining:'#f5a623',shopping:'#2f7cf6',transport:'#1d1d1f',life:'#2ea56b',other:'#aeaeb2'};
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
    var v=P.judge(seg,state.rules);
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

/* 账单 */
var billFilter={q:'',cat:'',src:'',days:30};
function friendlyDate(ds){var today=todayStr();if(ds===today)return'今天';
  var y=new Date();y.setDate(y.getDate()-1);var yd=y.getFullYear()+'-'+pad(y.getMonth()+1)+'-'+pad(y.getDate());
  if(ds===yd)return'昨天';return ds.slice(5).replace('-','/')+' 周'+'日一二三四五六'[new Date(ds).getDay()];}

function renderBill(){
  var ul=$('billGroups');ul.innerHTML='';
  var list=state.entries.slice();
  if(billFilter.q)list=list.filter(function(e){return(e.merchant||'').toLowerCase().indexOf(billFilter.q.toLowerCase())>=0||(e.raw||'').toLowerCase().indexOf(billFilter.q.toLowerCase())>=0;});
  if(billFilter.cat)list=list.filter(function(e){return e.category===billFilter.cat;});
  if(billFilter.src)list=list.filter(function(e){return(e.source||'')===billFilter.src;});
  if(billFilter.days){var d=new Date();d.setDate(d.getDate()-billFilter.days);var cut=d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());list=list.filter(function(e){return e.date>=cut;});}
  var out=list.filter(function(e){return!e.income;}).reduce(function(s,e){return s+e.amount;},0);
  var inc=list.filter(function(e){return e.income;}).reduce(function(s,e){return s+e.amount;},0);
  var sum=$('billSummary');
  if(list.length){sum.hidden=false;sum.innerHTML='<div class="bs"><span>支出</span><b>¥'+fmt(out)+'</b></div><div class="bs income"><span>收入</span><b>¥'+fmt(inc)+'</b></div>';}
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
  $('ruleStat').innerHTML='<div class="rs"><b>'+total+'</b><span>总规则</span></div><div class="rs on"><b>'+enabled+'</b><span>已启用</span></div><div class="rs warn"><b>'+(total-enabled)+'</b><span>已停用</span></div>';
  var ul=$('ruleList');ul.innerHTML='';
  if(!state.rules.length){ul.innerHTML='<div class="empty">还没有学习规则 —— 在账单里改一次分类就会自动记住</div>';return;}
  state.rules.forEach(function(r,i){
    var on=r.enabled!==false;
    var li=document.createElement('div');li.className='rule-item';
    var ic=document.createElement('span');ic.className='b-ic';ic.style.background=CAT_COLOR[r.category]||'#aeaeb2';
    ic.textContent=(CATS[r.category]||'其').slice(0,1);
    var m=document.createElement('div');m.className='rm';
    m.innerHTML='<b></b><span></span>';
    m.children[0].textContent=r.merchant+' → '+(CATS[r.category]||r.category);
    m.children[1].textContent='商户匹配';
    var sw=document.createElement('label');sw.className='switch';
    var cb=document.createElement('input');cb.type='checkbox';cb.checked=on;
    cb.addEventListener('change',function(){r.enabled=cb.checked;save();renderRules();});
    var i2=document.createElement('i');sw.appendChild(cb);sw.appendChild(i2);
    var del=document.createElement('button');del.className='link';del.textContent='删除';
    del.addEventListener('click',function(){state.rules.splice(i,1);save();renderRules();toast('已删除规则：'+r.merchant,'ok');});
    li.appendChild(ic);li.appendChild(m);li.appendChild(sw);li.appendChild(del);
    ul.appendChild(li);
  });
}

function newRule(){
  $('ruleMerchant').value='';ruleCat.val='other';renderCatPick('ruleCatPick','other');showSheet('ruleSheet');
}
function saveRule(){
  var m=$('ruleMerchant').value.trim();
  if(!m){toast('请填写匹配条件','warn');return;}
  var rules=P.learn(m,ruleCat.val,state.rules);
  var found=rules.find(function(x){return x.merchant.toLowerCase()===m.toLowerCase();});
  if(found)found.enabled=true;
  state.rules=rules;save();hideSheet('ruleSheet');renderRules();
  toast('已保存规则：'+m+' → '+(CATS[ruleCat.val]||''),'ok');
}

/* 分析 */
var statRange='week';
function renderStats(){
  var entries=state.entries;
  function inRange(e){
    if(statRange==='week'){var d=new Date();d.setDate(d.getDate()-6);var cut=d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());return e.date>=cut;}
    if(statRange==='month')return e.date.slice(0,7)===todayStr().slice(0,7);
    return true;
  }
  var rel=entries.filter(inRange).filter(function(e){return!e.income;});
  var total=rel.reduce(function(s,e){return s+e.amount;},0);
  var last7Start=(function(){var d=new Date();d.setDate(d.getDate()-6);return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());})();
  var last7=entries.filter(function(e){return!e.income&&e.date>=last7Start;});
  var last7Total=last7.reduce(function(s,e){return s+e.amount;},0);

  if(total>0){
    $('statMain').innerHTML='<div class="st-main"><b>¥'+fmt(total)+'</b><span>总支出</span></div>'+
      '<div class="st-grid"><div class="g"><span>日均支出</span><b>¥'+fmt(last7Total/7)+'</b></div><div class="g"><span>交易笔数</span><b>'+last7.length+'</b></div></div>';
  }else $('statMain').innerHTML='<div class="empty">该范围内暂无支出数据</div>';

  var byCat={};rel.forEach(function(e){byCat[e.category]=(byCat[e.category]||0)+e.amount;});
  var catEl=$('statCats');catEl.innerHTML='';
  var keys=Object.keys(CATS).filter(function(k){return byCat[k];}).sort(function(a,b){return byCat[b]-byCat[a];});
  if(!keys.length)catEl.innerHTML='<div class="empty">暂无分类数据</div>';
  keys.forEach(function(k){
    var v=byCat[k],pct=Math.round(v/total*100)||0;
    var row=document.createElement('div');row.className='cat-row';
    row.innerHTML='<span class="cic" style="background:'+(CAT_COLOR[k]||'#aeaeb2')+'">'+CATS[k].slice(0,1)+'</span>'+
      '<div class="cmain"><b>'+CATS[k]+'</b><div class="track"><i style="width:'+pct+'%"></i></div></div>'+
      '<div class="cval">¥'+fmt(v)+'<span>'+pct+'%</span></div>';
    catEl.appendChild(row);
  });

  var days=[];for(var i=6;i>=0;i--){(function(n){var d=new Date();d.setDate(d.getDate()-n);
    var ds=d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());
    days.push({label:n===0?'今天':ds.slice(5),total:entries.filter(function(e){return!e.income&&e.date===ds;}).reduce(function(s,e){return s+e.amount;},0)});
  })(i);}
  var max=Math.max.apply(null,days.map(function(d){return d.total;}))||1;
  var de=$('statDays');de.innerHTML='';
  days.forEach(function(d){
    var h=Math.max(3,Math.round(d.total/max*100));
    de.innerHTML+='<div class="dcol"><div class="dval">'+(d.total?'¥'+fmt(d.total):'')+'</div><div class="dbar" style="height:'+h+'%"></div><div class="dlab">'+d.label+'</div></div>';
  });

  var ins=$('statInsight');
  if(!total)ins.innerHTML='<p>暂无足够数据生成消费洞察，先记一笔账吧。</p>';
  else{var top=keys[0];ins.innerHTML='<p>「'+(CATS[top]||'其他')+'」是最大支出项，占 '+Math.round(byCat[top]/total*100)+'%（¥'+fmt(byCat[top])+'）。近 7 天共 '+last7.length+' 笔，日均 ¥'+fmt(last7Total/7)+'。</p>';}
}

/* 最近一笔 */
function renderRecent(){
  if(!state.entries.length){$('recentCard').hidden=true;return;}
  var last=state.entries.slice().sort(function(a,b){return(b.date+b.time).localeCompare(a.date+a.time);})[0];
  $('recentCard').hidden=false;
  $('recentIc').textContent=(last.source||'未').slice(0,1);
  $('recentIc').style.background=SRC_COLOR[last.source]||'#aeaeb2';
  $('recentM').textContent=last.merchant||'未知商户';
  $('recentSub').textContent=(CATS[last.category]||'其他')+' · '+(last.source||'未知');
  $('recentA').textContent=(last.income?'+':'-')+'¥'+fmt(last.amount);
  $('recentT').textContent=last.time||'';
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
function init(){
  initState();
  document.querySelectorAll('.tabbar button').forEach(function(b){b.addEventListener('click',function(){goPage(b.dataset.page);});});
  document.querySelectorAll('.seg-btn[data-mode]').forEach(function(b){
    b.addEventListener('click',function(){
      document.querySelectorAll('.seg-btn[data-mode]').forEach(function(x){x.classList.remove('active');});
      b.classList.add('active');
      $('paneText').hidden=b.dataset.mode!=='text';
      $('paneShot').hidden=b.dataset.mode!=='shot';
    });
  });
  $('btnParse').addEventListener('click',function(){parseToConfirm($('pushText').value);});
  $('pushText').addEventListener('keydown',function(ev){if(ev.key==='Enter'&&!ev.shiftKey){ev.preventDefault();parseToConfirm($('pushText').value);}});
  $('examples').addEventListener('click',function(ev){var li=ev.target.closest('li[data-text]');if(li){$('pushText').value=li.dataset.text;parseToConfirm(li.dataset.text);}});
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
  $('btnAllBill').addEventListener('click',function(){goPage('bill');});
  $('btnBillTools').addEventListener('click',function(){$('billMenu').hidden=!$('billMenu').hidden;});
  $('btnCsv').addEventListener('click',exportCsv);
  $('billSearch').addEventListener('input',function(){billFilter.q=this.value.trim();renderBill();});
  $('fTime').addEventListener('click',function(){var v=prompt('时间范围：1近7天 2近30天 3全部','2');if(v==='1'){billFilter.days=7;this.textContent='近7天 ▾';}else if(v==='3'){billFilter.days=0;this.textContent='全部 ▾';}else{billFilter.days=30;this.textContent='近30天 ▾';}renderBill();});
  $('fCat').addEventListener('click',function(){var v=prompt('分类筛选（0清空）：\n'+Object.keys(CATS).map(function(k){return k+'='+CATS[k];}).join('\n'),'0');if(!v)return;if(v==='0'){billFilter.cat='';this.textContent='分类 ▾';}else{billFilter.cat=v;this.textContent=(CATS[v]||v)+' ▾';}renderBill();});
  $('fSrc').addEventListener('click',function(){var v=prompt('支付方式（0清空）：微信/支付宝/银联','0');if(!v)return;if(v==='0'){billFilter.src='';this.textContent='支付方式 ▾';}else{billFilter.src=v;this.textContent=v+' ▾';}renderBill();});
  $('btnNewRule').addEventListener('click',newRule);
  $('btnRuleSave').addEventListener('click',saveRule);
  document.querySelectorAll('#statRange .seg-btn').forEach(function(b){b.addEventListener('click',function(){document.querySelectorAll('#statRange .seg-btn').forEach(function(x){x.classList.remove('active');});b.classList.add('active');statRange=b.dataset.range;renderStats();});});
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
document.addEventListener('DOMContentLoaded',init);
})();