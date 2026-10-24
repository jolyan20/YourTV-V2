const fs=require('fs'),vm=require('vm'),assert=require('assert');
const src=fs.readFileSync(require('path').join(__dirname,'../src/template.html'),'utf8');
const fn=src.slice(src.indexOf('function playAdhan(cur){'),src.indexOf('// short sounds',src.indexOf('function playAdhan(cur){')));
function setup(files,mode='full'){
 let tags=[],restored=0; const classes=()=>({add(){},remove(){}});
 const host={classList:classes(),replaceChildren(){},appendChild(){},textContent:''},week={classList:classes()};
 const ctx={ADHAN_DONE:'',ADHAN_FINISHED:'',ADHAN_ACTIVE:false,todayKey:()=> 'day',CONFIG:{widget:false},adhanMode:()=>mode,CTRL:{files},prayerName:()=> 'الظهر',renderPanel:()=>restored++,setTimeout:()=>1,clearTimeout(){},document:{getElementById:()=>host,querySelector:()=>week,createElement:(tag)=>{const m={tag,currentTime:0,setAttribute(){},removeAttribute(){},load(){},pause(){},play(){return Promise.resolve();}};tags.push(m);return m;}}};
 vm.createContext(ctx);vm.runInContext(fn,ctx);return {ctx,tags,restored:()=>restored,play:()=>ctx.playAdhan({key:'Dhuhr'})};
}
const f={size:2000,at:1};
let t=setup({'adhan-video-main':f,'adhan-main':f});t.play();assert.equal(t.tags[0].tag,'video');assert(t.ctx.ADHAN_ACTIVE);t.tags[0].onended();assert(!t.ctx.ADHAN_ACTIVE);assert.equal(t.restored(),1);t.play();assert.equal(t.tags.length,1);
t=setup({'adhan-video-main':f,'adhan-main':f});t.play();t.tags[0].onerror();assert.equal(t.tags[1].tag,'audio');t.tags[1].onended();assert.equal(t.restored(),1);
t=setup({'adhan-video-main':f});t.play();t.tags[0].onerror();assert(!t.ctx.ADHAN_ACTIVE);assert.equal(t.restored(),1);
t=setup({'adhan-video-main':f},'takbir');t.play();t.tags[0].currentTime=30;t.tags[0].ontimeupdate();assert(!t.ctx.ADHAN_ACTIVE);
t=setup({'adhan-video-main':f},'off');t.play();assert.equal(t.tags.length,0);
t=setup({});t.play();assert(!t.ctx.ADHAN_ACTIVE);
const when=src.slice(src.indexOf('function whenText('),src.indexOf('\nfunction seriesSlide'));
const c={CONFIG:{timezone:'Asia/Riyadh'},GREG:'ar-SA',IDF:(l,o)=>new Intl.DateTimeFormat(l,o),esc:String,ar:String,cdn:()=> 'COUNTDOWN'};vm.createContext(c);vm.runInContext(when,c);
let now=Date.now();assert(c.whenText({next:{t:now+2*864e5}},now).includes('متبقي 2 يوم'));assert(c.whenText({next:{t:now+1000}},now).includes('COUNTDOWN'));assert(c.whenText({},now).includes('لم يُعلن'));
const ctl=fs.readFileSync(require('path').join(__dirname,'../assets/shared/control.html'),'utf8');for(const m of ctl.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g))new Function(m[1]);
console.log('PASS video priority, audio fallback, completion, failures, takbir, off, duplicate prevention; series date/countdown and control syntax');

const msg=src.slice(src.indexOf('function renderMessage(){'),src.indexOf('// «مهامي»',src.indexOf('function renderMessage(){')));
let visible=false,html='';const el={dataset:{},classList:{toggle:(n,v)=>visible=v}};
const mc={CTRL:{message:{text:'<script>bad</script>',sender:'أحمد',style:'urgent',at:Date.now(),until:Date.now()+300000}},document:{getElementById:()=>el},setHTML:(e,h)=>html=h,esc:s=>String(s).replaceAll('<','&lt;').replaceAll('>','&gt;'),Date};vm.createContext(mc);vm.runInContext(msg,mc);mc.renderMessage();assert(visible);assert(html.includes('تحذير'));assert(html.includes('أحمد'));assert(!html.includes('<script>'));mc.CTRL.message.until=Date.now()-1;mc.renderMessage();assert(!visible);
mc.CTRL.message={text:'expired legacy',at:Date.now()-301000};mc.renderMessage();assert(!visible);
const occ=src.slice(src.indexOf('function renderOccasion('),src.indexOf('let FAM_CACHE'));
let out={};const parent={querySelector:()=>null};const label={parentNode:parent,id:'label'},days={id:'days'},sub={id:'sub'};
const oc={setHTML:(e,v)=>out[e.id]=v,esc:String,ar:String,hijriDate:()=> '١٤٤٨ هـ',gregDay:()=> 'الجمعة',gregDate:()=> '23 أكتوبر',cdn:()=> 'TIMER',Date,occIcon:()=>''};vm.createContext(oc);vm.runInContext(occ,oc);
const event={t:Date.now()+3*3600000+2*86400000,days:2,o:{greet:'اليوم'},label:'مناسبة'};oc.renderOccasion(event,label,days,sub);assert(!out.days.includes('TIMER'));event.t=Date.now()+3*3600000+60000;oc.renderOccasion(event,label,days,sub);assert(out.days.includes('TIMER'));
console.log('PASS message types, sender escaping and expiry; countdown only within 24 hours');
