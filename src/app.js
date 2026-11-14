import { Capacitor, registerPlugin } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";
import { LocalNotifications } from "@capacitor/local-notifications";
import { App } from "@capacitor/app";
import { STR, DATA_AR } from "./i18n.js";

const NATIVE = Capacitor.isNativePlatform();
const DAY = 86400000;
const COLOR_IDS = ["teal","blue","orange","purple","rose","slate"];
const SOURCE_IDS = ["reported","confirmed","unconfirmed"];
const PATTERN_IDS = ["daily","alternate","confirm"];

/* ---------- Language and display mode ---------- */
let ui = {lang:"en", elderly:false};
const t = (key, vars)=>{ let s=(STR[ui.lang]&&STR[ui.lang][key]); if(s==null) s=STR.en[key]; if(s==null) s=key;
  if(vars) for(const k in vars) s=s.split("{"+k+"}").join(vars[k]); return s; };
const AR = ()=>ui.lang==="ar";
// Shows plan text in Arabic when it is text from the reported plan; your own text is shown as typed.
const tr = s=>{ if(!s) return s; return AR() && DATA_AR[s] ? DATA_AR[s] : s; };
const doseText = s=>{ if(!s) return s; if(!AR()) return s; if(DATA_AR[s]) return DATA_AR[s]; if(/^no dose$/i.test(s)) return t("no_dose"); return s.replace(/(\d+(?:\.\d+)?)\s*mg\b/gi,"$1 ملغ"); };
const LOCALE = ()=>AR() ? "ar-u-ca-gregory-nu-latn" : "en-US";
const isNoDose = s=>/^(no dose|لا جرعة|بدون جرعة)$/i.test((s||"").trim());

/* ---------- Starting plan: empty; each person adds their own medicines ---------- */
const DEFAULT_REMINDERS = {enabled:true, repeatMin:10, stopAfterMin:120};
function ymdLocal(d){return d.getFullYear()+"-"+String(d.getMonth()+1).padStart(2,"0")+"-"+String(d.getDate()).padStart(2,"0")}
function makeEmptyPlan(){
  const today=new Date(), end=new Date(today.getFullYear()+1,today.getMonth(),today.getDate());
  return {start:ymdLocal(today), end:ymdLocal(end), reminders:{...DEFAULT_REMINDERS}, meds:[]};
}

const clone = o=>JSON.parse(JSON.stringify(o));
let plan = makeEmptyPlan();
// Questions the person wants to ask their doctor; also printed in the report.
let questions = [];
let logs = {};
// Every Taken / Skip / Snooze / note, with its exact time. Used by My Notes and the report.
let events = [];

/* ---------- Storage ---------- */
async function loadState(){
  try{
    const u=await Preferences.get({key:"ui"});
    if(u.value){ const v=JSON.parse(u.value)||{}; ui.lang=v.lang==="ar"?"ar":"en"; ui.elderly=!!v.elderly; }
    const p=await Preferences.get({key:"plan"});
    if(p.value){const v=JSON.parse(p.value); if(v&&Array.isArray(v.meds)) plan=v;}
    else await Preferences.set({key:"plan",value:JSON.stringify(plan)});
    if(!plan.reminders) plan.reminders={...DEFAULT_REMINDERS};
    const q=await Preferences.get({key:"questions"});
    if(q.value){ const v=JSON.parse(q.value); if(Array.isArray(v)) questions=v.filter(x=>typeof x==="string"); }
    plan.meds.forEach(m=>{ if(m.remind===undefined) m.remind=true; });
    const l=await Preferences.get({key:"logs"});
    if(l.value){ logs=JSON.parse(l.value)||{}; }
    const ev=await Preferences.get({key:"events"});
    if(ev.value){ const v=JSON.parse(ev.value); if(Array.isArray(v)) events=v; }
  }catch(e){ showStore(t("err_read",{e:(e&&e.message||e)})); }
}
let saveLogsTimer=null;
function persistLogs(){
  clearTimeout(saveLogsTimer);
  saveLogsTimer=setTimeout(()=>{ Preferences.set({key:"logs",value:JSON.stringify(logs)}).catch(e=>showStore(t("err_save",{e:(e&&e.message||e)}))); },300);
}
function persistPlan(){
  return Preferences.set({key:"plan",value:JSON.stringify(plan)}).catch(e=>showStore(t("err_plan",{e:(e&&e.message||e)})));
}
let saveEventsTimer=null;
function persistEvents(){
  clearTimeout(saveEventsTimer);
  saveEventsTimer=setTimeout(()=>{ Preferences.set({key:"events",value:JSON.stringify(events)}).catch(e=>showStore(t("err_save",{e:(e&&e.message||e)}))); },300);
}
const ACTION_OF = {"Taken":"taken","Skipped":"skip"};
function addEvent(ev){
  if(ev.id && events.some(x=>x.id===ev.id)) return false;
  if(!ev.id) ev.id="a"+Date.now()+Math.random().toString(36).slice(2,6);
  if(!ev.date && ev.key) ev.date=ev.key.slice(0,10);
  if(!ev.med && ev.key) ev.med=ev.key.slice(11);
  if(!ev.at) ev.at=Date.now();
  events.push(ev); persistEvents(); return true;
}
function persistUi(){ return Preferences.set({key:"ui",value:JSON.stringify(ui)}).catch(()=>{}); }

/* ---------- Dates and schedule ---------- */
function parseYmd(s){ if(!/^\d{4}-\d{2}-\d{2}$/.test(s||"")) return null; const [y,m,d]=s.split("-").map(Number); return new Date(y,m-1,d); }
function ymd(d){return d.getFullYear()+"-"+String(d.getMonth()+1).padStart(2,"0")+"-"+String(d.getDate()).padStart(2,"0")}
function atMidnight(d){return new Date(d.getFullYear(),d.getMonth(),d.getDate())}
function daysBetween(a,b){return Math.round((atMidnight(b)-atMidnight(a))/DAY)}
function addDays(d,n){return new Date(d.getFullYear(),d.getMonth(),d.getDate()+n)}
const START = ()=>parseYmd(plan.start), END = ()=>parseYmd(plan.end);
function clampDate(d){if(d<START())return START();if(d>END())return END();return atMidnight(d)}
const sortedMeds = ()=>plan.meds.slice().sort((a,b)=>(a.time||"").localeCompare(b.time||""));
const hhmm = d=>String(d.getHours()).padStart(2,"0")+":"+String(d.getMinutes()).padStart(2,"0");

function scheduled(med, d){
  const day = atMidnight(d);
  if(day<START()||day>END()) return null;
  for(const p of med.phases){
    const s=parseYmd(p.start), e=p.end?parseYmd(p.end):null;
    if(!s || day<s || (e && day>e)) continue;
    if(p.pattern==="confirm") return {kind:"unknown",text:(p.doses[0]||"").trim()||t("confirm_fallback"),note:p.note,source:p.source||"unconfirmed"};
    const list=(p.pattern==="alternate"?p.doses:[p.doses[0]]).map(x=>(x||"").trim());
    const tx=list[daysBetween(s,day)%list.length]||"";
    if(!tx || isNoDose(tx)) return {kind:"none",text:"No dose",note:p.note,source:p.source};
    return {kind:"dose",text:tx,note:p.note,source:p.source};
  }
  return null;
}

// Stored status values stay in English; only their display is translated.
const STATUSES = [
  {v:"Taken",cls:"taken"},{v:"Skipped",cls:"skipped"},{v:"Held by clinician",cls:"held"},
  {v:"No dose per reported plan",cls:"nodose"},{v:"Needs confirmation",cls:"confirm"}
];
const STATUS_CLS = {"Taken":"taken","Skipped":"skipped","Held by clinician":"held","No dose per reported plan":"nodose","Needs confirmation":"confirm"};
const stLabel = v=>STATUS_CLS[v]?t("st_"+STATUS_CLS[v]):v;
const stShort = v=>STATUS_CLS[v]?t("sh_"+STATUS_CLS[v]):v;

let selected = null;
const $ = id=>document.getElementById(id);
const fmtLong = d=>d.toLocaleDateString(LOCALE(),{weekday:"long",day:"numeric",month:"long",year:"numeric"});
const fmtShort = d=>d.toLocaleDateString(LOCALE(),{weekday:"short",day:"numeric",month:"short",year:"numeric"});
const isToday = d=>ymd(d)===ymd(new Date());
function weekStart(d){const x=atMidnight(d);const dow=(x.getDay()+6)%7;return new Date(x.getTime()-dow*DAY)}
function entry(d,medId){return logs[ymd(d)+"_"+medId]||null}
function effectiveStatus(d,med){
  const e=entry(d,med.id); if(e&&e.status) return e.status;
  const s=scheduled(med,d); if(!s) return null;
  if(s.kind==="none") return "No dose per reported plan";
  if(s.kind==="unknown") return "Needs confirmation";
  return "Not logged";
}
const cvar = c=>"var(--"+(c||"slate")+")", csvar = c=>"var(--"+(c||"slate")+"-soft)";
function showStore(msg){const el=$("storeMsg");el.textContent=msg||"";el.hidden=!msg}

function setLog(medId,dateKey,patch){
  const key=dateKey+"_"+medId;
  const prev=(logs[key]&&logs[key].status)||"";
  const cur=Object.assign({status:"",time:"",note:""},logs[key]||{},patch);
  if(patch.status!==undefined && patch.status!==prev){
    // Undoing a status removes its action from the timeline; any note on it is kept as a note.
    if(prev && ACTION_OF[prev]){
      events=events.filter(e=>{ if(e.key===key && e.action===ACTION_OF[prev]){ if(e.note||e.audio){ e.action="note"; return true; } return false; } return true; });
      persistEvents();
    }
    if(ACTION_OF[patch.status]){
      const s=scheduled(plan.meds.find(m=>m.id===medId)||{phases:[]},parseYmd(dateKey));
      addEvent({key,date:dateKey,med:medId,action:ACTION_OF[patch.status],at:Date.now(),time:hhmm(new Date()),dose:s?s.text:"",note:"",source:"app"});
    }
  }
  if(!cur.status && !cur.time && !cur.note) delete logs[key]; else logs[key]=cur;
  persistLogs();
  if(patch.status!==undefined){ scheduleReminders(); }
}
function markTaken(m,dateKey){
  const patch={status:"Taken"};
  if(isToday(selected)) patch.time=hhmm(new Date());
  openRows.delete(m.id); setLog(m.id,dateKey,patch); render(); saved();
}
let toastTimer=null;
function saved(msgKey){
  const el=$("toast"); el.textContent=t(msgKey||"saved_toast"); el.hidden=false;
  clearTimeout(toastTimer); toastTimer=setTimeout(()=>{el.hidden=true},1600);
  if(NATIVE){ try{ DoseAlarm.vibrate(); }catch(_){} }
}

/* ---------- Static text and mode ---------- */
function applyUi(){
  const root=document.documentElement;
  root.lang=ui.lang; root.dir=AR()?"rtl":"ltr";
  root.classList.toggle("elder",ui.elderly);
  document.title=t("app_title");
  document.querySelectorAll("[data-i18n]").forEach(e=>{ e.textContent=t(e.getAttribute("data-i18n")); });
  document.querySelectorAll("[data-i18n-html]").forEach(e=>{ e.innerHTML=t(e.getAttribute("data-i18n-html")); });
  const lb=$("langBtn"); lb.textContent=t("lang_toggle"); lb.lang=AR()?"en":"ar";
  const eb=$("elderBtn"); eb.textContent=ui.elderly?t("elder_on"):t("elder_off"); eb.setAttribute("aria-pressed",String(ui.elderly));
}

/* ---------- Tracker view ---------- */
function renderWeek(){
  const ws=weekStart(selected), wrap=$("week"); wrap.textContent="";
  for(let i=0;i<7;i++){
    const d=addDays(ws,i);
    const b=document.createElement("button");
    b.className="day"+(isToday(d)?" today":"");
    const inRange=d>=START()&&d<=END();
    b.disabled=!inRange;
    if(ymd(d)===ymd(selected)) b.setAttribute("aria-current","date");
    b.setAttribute("aria-label",fmtLong(d));
    const dn=document.createElement("span");dn.className="dn";dn.textContent=d.toLocaleDateString(LOCALE(),{weekday:AR()?"narrow":"short"});
    const dd=document.createElement("span");dd.className="dd";dd.textContent=d.getDate();
    const dots=document.createElement("span");dots.className="dots";
    if(inRange) sortedMeds().forEach(m=>{
      const st=effectiveStatus(d,m); if(!st) return;
      const dot=document.createElement("span");
      dot.className="dot"+(st==="Taken"?" done":"")+(st==="Needs confirmation"?" flag":"");
      dot.style.setProperty("--c",cvar(m.color)); dots.appendChild(dot);
    });
    b.append(dn,dd,dots);
    b.addEventListener("click",()=>{selected=d;render()});
    wrap.appendChild(b);
  }
  $("prevWeek").disabled = ws<=START();
  $("nextWeek").disabled = addDays(ws,7)>END();
  $("prevDay").disabled = atMidnight(selected)<=START();
  $("nextDay").disabled = atMidnight(selected)>=END();
}

function renderSummary(){
  const counts={taken:0,open:0,skipped:0,flag:0};
  plan.meds.forEach(m=>{const st=effectiveStatus(selected,m);
    if(st==="Taken")counts.taken++;else if(st==="Not logged")counts.open++;else if(st==="Skipped")counts.skipped++;else if(st==="Needs confirmation"||st==="Held by clinician")counts.flag++;});
  const s=$("summary"); s.textContent="";
  const add=(cls,txt)=>{const c=document.createElement("span");c.className="chip "+cls;c.textContent=txt;s.appendChild(c)};
  add("ok",t("c_taken",{n:counts.taken})); add("open",t("c_open",{n:counts.open}));
  if(counts.skipped) add("bad",t("c_skipped",{n:counts.skipped}));
  if(counts.flag) add("warn",t("c_flag",{n:counts.flag}));
}

function nextDueId(){
  if(!isToday(selected)) return null;
  for(const m of sortedMeds()){ if(effectiveStatus(selected,m)==="Not logged") return m.id; }
  return null;
}

function tagEl(kind,label){const s=document.createElement("span");s.className="tag "+kind;s.textContent=label;return s}
const SRC_TAG = {reported:["rep","tag_rep"],confirmed:["conf","tag_conf"],unconfirmed:["unc","tag_unc"]};
const openRows = new Set();
let openDay = null;
const medName = m=>tr(m.name)||t("unnamed");
const doseDisplay = s=>s.kind==="none"?t("no_dose"):doseText(s.text);

function renderEmptyPlan(wrap){
  const card=document.createElement("div"); card.className="ed-med emptyplan"; card.style.setProperty("--c","var(--blue)");
  const h=document.createElement("h3"); h.textContent=t("empty_title");
  const p=document.createElement("p"); p.className="ed-intro"; p.textContent=t("empty_hint");
  const b=document.createElement("button"); b.type="button"; b.className="btn primary bigbtn"; b.textContent=t("add_first");
  b.addEventListener("click",startFirstMed);
  card.append(h,p,b); wrap.appendChild(card);
}
function newMed(){
  return {id:"m"+Date.now().toString(36),name:"",generic:"",time:"",slotLabel:"",color:"blue",remind:true,instrNote:"",prefNote:"",openNote:"",timeConfirmed:false,
    phases:[{start:plan.start,end:"",pattern:"daily",doses:[""],note:"",source:"reported"}]};
}
function startFirstMed(){ openEditor(); draft.meds.push(newMed()); renderEditor(); }

function renderSlots(){
  if(!plan.meds.length){ const w=$("slots"); w.textContent=""; renderEmptyPlan(w); return; }
  if(ui.elderly){ renderElderSlots(); return; }
  const wrap=$("slots"); wrap.textContent="";
  const next=nextDueId();
  const dateKey=ymd(selected);
  if(openDay!==dateKey){openRows.clear();openDay=dateKey;}
  let any=false;
  sortedMeds().forEach(m=>{
    const s=scheduled(m,selected); if(!s) return;
    any=true;
    const e=entry(selected,m.id)||{};
    const st=effectiveStatus(selected,m);
    const logged=!!e.status;
    const isOpen=openRows.has(m.id);
    const nmText=medName(m);

    const card=document.createElement("article"); card.className="card"+(next===m.id?" next":"")+(logged?" done":"");
    card.style.setProperty("--c",cvar(m.color)); card.style.setProperty("--cs",csvar(m.color));

    const line=document.createElement("div"); line.className="line";
    const tm=document.createElement("div"); tm.className="time"; tm.textContent=m.time||"--:--";
    const tl=document.createElement("small"); tl.textContent=tr(m.slotLabel)||""; tm.appendChild(tl);
    const nm=document.createElement("div"); nm.className="name"; nm.textContent=nmText;
    const gn=document.createElement("small"); gn.textContent=logged&&e.time?t("logged_at",{t:e.time}):(tr(m.generic)||""); nm.appendChild(gn);
    const dose=document.createElement("div"); dose.className="dose"+(s.kind==="none"?" nodose":"")+(s.kind==="unknown"?" unk":""); dose.textContent=doseDisplay(s);

    const act=document.createElement("div"); act.className="act";
    if(logged || s.kind!=="dose"){
      const bd=document.createElement("span"); bd.className="badge "+(STATUS_CLS[st]||"nodose"); bd.textContent=stShort(st); act.appendChild(bd);
    }else{
      const q=document.createElement("button"); q.type="button"; q.className="quick"; q.innerHTML=t("mark_taken_html");
      q.setAttribute("aria-label",t("aria_mark",{name:nmText,dose:doseDisplay(s)}));
      q.addEventListener("click",()=>markTaken(m,dateKey));
      act.appendChild(q);
    }
    const chev=document.createElement("button"); chev.type="button"; chev.className="chev"; chev.textContent="▾";
    chev.setAttribute("aria-expanded",String(isOpen)); chev.setAttribute("aria-label",t(isOpen?"aria_hide":"aria_show",{name:nmText}));
    chev.addEventListener("click",()=>{ if(openRows.has(m.id)) openRows.delete(m.id); else openRows.add(m.id); renderSlots(); });
    act.appendChild(chev);
    line.append(tm,nm,dose,act);
    card.appendChild(line);
    if(next===m.id && !isOpen){const nl=document.createElement("div");nl.className="nextline";nl.textContent=t("next_line");card.appendChild(nl)}
    if(!isOpen){ wrap.appendChild(card); return; }

    const detail=document.createElement("div"); detail.className="detail";
    const meta=document.createElement("div"); meta.className="meta";
    const addMeta=(kind,labelKey,txt)=>{ if(!txt) return; const p=document.createElement("div"); p.append(tagEl(kind,t(labelKey)),document.createTextNode(txt)); meta.appendChild(p); };
    const st2=SRC_TAG[s.source]||SRC_TAG.reported;
    addMeta(st2[0],st2[1],tr(s.note)||t("no_note"));
    addMeta(m.timeConfirmed?"conf":"rep", m.timeConfirmed?"tag_conf":"tag_rep", tr(m.instrNote));
    addMeta(m.timeConfirmed?"conf":"pref", m.timeConfirmed?"tag_conf":"tag_pref", tr(m.prefNote));
    addMeta("unc","tag_unc", tr(m.openNote));
    if(next===m.id){const p=document.createElement("div");p.style.fontWeight="700";p.style.color=cvar(m.color);p.textContent=t("next_detail");meta.prepend(p)}
    if(!logged && s.kind==="dose"){const p=document.createElement("div");p.textContent=t("not_logged_note");meta.appendChild(p)}
    if(m.remind===false){const p=document.createElement("div");p.textContent=t("rem_off_med");meta.appendChild(p)}

    const status=document.createElement("div"); status.className="status"; status.setAttribute("role","group"); status.setAttribute("aria-label",t("aria_status",{name:nmText}));
    STATUSES.forEach(o=>{
      const b=document.createElement("button"); b.type="button"; b.className="st "+o.cls; b.textContent=stLabel(o.v);
      const pressed = e.status ? e.status===o.v : (s.kind!=="dose" && st===o.v);
      b.setAttribute("aria-pressed", String(pressed));
      b.addEventListener("click",()=>{
        const newStatus = (e.status===o.v)?"":o.v;
        const patch={status:newStatus};
        if(newStatus==="Taken" && !e.time && isToday(selected)) patch.time=hhmm(new Date());
        setLog(m.id,dateKey,patch); render(); saved();
      });
      status.appendChild(b);
    });

    const row=document.createElement("div"); row.className="row";
    const tid="t-"+m.id; const lab=document.createElement("label"); lab.htmlFor=tid; lab.textContent=t("actual_time");
    const ti=document.createElement("input"); ti.type="time"; ti.id=tid; ti.value=e.time||"";
    ti.addEventListener("change",()=>{setLog(m.id,dateKey,{time:ti.value});renderWeek()});
    row.append(lab,ti);

    const nid="n-"+m.id; const nl=document.createElement("label"); nl.htmlFor=nid; nl.textContent=t("notes");
    const na=document.createElement("textarea"); na.id=nid; na.value=e.note||""; na.placeholder=t("notes_ph");
    na.addEventListener("input",()=>setLog(m.id,dateKey,{note:na.value}));

    detail.append(meta,status,row,nl,na);
    card.appendChild(detail); wrap.appendChild(card);
  });
  if(!any){const p=document.createElement("p");p.className="foot";p.textContent=t("none_scheduled");wrap.appendChild(p)}
}

// Elderly mode: one large card per medicine with a single big button.
function renderElderSlots(){
  const wrap=$("slots"); wrap.textContent="";
  const next=nextDueId();
  const dateKey=ymd(selected);
  let any=false;
  sortedMeds().forEach(m=>{
    const s=scheduled(m,selected); if(!s) return;
    any=true;
    const e=entry(selected,m.id)||{};
    const st=effectiveStatus(selected,m);
    const logged=!!e.status;
    const card=document.createElement("article"); card.className="ecard"+(next===m.id?" next":"")+(logged?" done":"");
    card.style.setProperty("--c",cvar(m.color));

    const top=document.createElement("div"); top.className="etop";
    const tm=document.createElement("span"); tm.className="etime"; tm.textContent=m.time||"--:--";
    const sl=document.createElement("span"); sl.className="eslot"; sl.textContent=tr(m.slotLabel)||"";
    top.append(tm,sl);
    const nm=document.createElement("div"); nm.className="ename"; nm.textContent=medName(m);
    card.append(top,nm);

    if(s.kind==="dose"){
      const ds=document.createElement("div"); ds.className="edose"; ds.textContent=doseText(s.text); card.appendChild(ds);
      if(m.instrNote){const n=document.createElement("div");n.className="enote";n.textContent=tr(m.instrNote);card.appendChild(n)}
    }

    const state=(cls,txt)=>{const d=document.createElement("div");d.className="estate "+cls;d.textContent=txt;card.appendChild(d)};
    const undo=()=>{const r=document.createElement("div");r.className="erow";const u=document.createElement("button");u.type="button";u.className="btn";u.textContent=t("el_undo");
      u.addEventListener("click",()=>{setLog(m.id,dateKey,{status:"",time:""});render();saved()});r.appendChild(u);card.appendChild(r)};

    if(logged){
      if(e.status==="Taken") state("taken","✓ "+(e.time?t("el_taken_at",{t:e.time}):t("el_taken")));
      else if(e.status==="Skipped") state("skipped",t("el_skipped"));
      else if(e.status==="Held by clinician") state("warn",t("el_held"));
      else state("none",stLabel(e.status));
      undo();
    }else if(s.kind==="none"){
      state("none",t("el_nodose"));
    }else if(s.kind==="unknown"){
      state("warn",t("el_unknown"));
    }else{
      const big=document.createElement("button"); big.type="button"; big.className="ebig"; big.textContent="✓ "+t("el_took");
      big.setAttribute("aria-label",t("aria_mark",{name:medName(m),dose:doseText(s.text)}));
      big.addEventListener("click",()=>markTaken(m,dateKey));
      card.appendChild(big);
      const r=document.createElement("div"); r.className="erow";
      const sk=document.createElement("button"); sk.type="button"; sk.className="btn"; sk.textContent=t("el_skip");
      sk.addEventListener("click",()=>{setLog(m.id,dateKey,{status:"Skipped"});render();saved()});
      r.appendChild(sk); card.appendChild(r);
    }
    wrap.appendChild(card);
  });
  if(!any){const p=document.createElement("p");p.className="foot";p.textContent=t("none_scheduled");wrap.appendChild(p)}
}

function render(){
  applyUi();
  if(!selected) selected=clampDate(new Date());
  selected=clampDate(selected);
  $("coverage").textContent=t("coverage",{a:fmtShort(START()),b:fmtShort(END())});
  const title=(isToday(selected)?t("today_prefix"):"")+fmtLong(selected);
  $("dayTitle").textContent=title; $("dayTitleElder").textContent=title;
  renderWeek(); renderSummary(); renderSlots(); renderRemindBox();
  renderBackupNotice();
  renderQuestions();
  if(notesOpen) renderNotes();
  if(backupOpen) renderBackup();
}

/* ---------- Questions for my doctor ---------- */
function persistQuestions(){ Preferences.set({key:"questions",value:JSON.stringify(questions)}).catch(e=>showStore(t("err_save",{e:(e&&e.message||e)}))); }
function renderQuestions(){
  const box=$("questionsList"); if(!box) return; box.textContent="";
  $("questionsCount").textContent=questions.length?" ("+questions.length+")":"";
  if(!questions.length) box.appendChild(Object.assign(document.createElement("p"),{className:"foot",textContent:t("q_empty")}));
  const ul=document.createElement("ul"); ul.className="qlist";
  questions.forEach((q,i)=>{
    const li=document.createElement("li");
    const sp=document.createElement("span"); sp.textContent=q;
    const rm=document.createElement("button"); rm.type="button"; rm.className="btn small"; rm.textContent="✕";
    rm.setAttribute("aria-label",t("q_remove",{q}));
    rm.addEventListener("click",()=>{ questions.splice(i,1); persistQuestions(); renderQuestions(); });
    li.append(sp,rm); ul.appendChild(li);
  });
  if(questions.length) box.appendChild(ul);
  const row=document.createElement("div"); row.className="row qadd";
  const lab=document.createElement("label"); lab.htmlFor="qInput"; lab.className="sr"; lab.textContent=t("q_new");
  const inp=document.createElement("input"); inp.type="text"; inp.id="qInput"; inp.placeholder=t("q_placeholder");
  const add=document.createElement("button"); add.type="button"; add.className="btn"; add.textContent=t("q_add");
  const doAdd=()=>{ const v=inp.value.trim(); if(!v) return; questions.push(v); persistQuestions(); renderQuestions(); saved(); };
  add.addEventListener("click",doAdd); inp.addEventListener("keydown",e=>{ if(e.key==="Enter") doAdd(); });
  row.append(lab,inp,add); box.appendChild(row);
}

/* ---------- Reminders (native full-screen alerts) ---------- */
const DoseAlarm = registerPlugin("DoseAlarm");
const DAYS_AHEAD = 7;          // alerts are set this many days ahead; opening the app extends them
const RELEASES_URL = "https://github.com/swaidi/jadwal/releases/latest";
let remindState = {notifications:true, overlay:true, fullScreen:true, exact:true, count:0, until:null, error:"", version:""};

async function refreshPermissions(){
  if(!NATIVE) return;
  try{
    const s=await DoseAlarm.getStatus();
    remindState.notifications=!!s.notifications; remindState.overlay=!!s.overlay;
    remindState.fullScreen=!!s.fullScreen; remindState.exact=!!s.exact; remindState.version=s.version||"";
  }catch(e){ remindState.error=String(e&&e.message||e); }
}

let schedTimer=null;
function scheduleReminders(){ clearTimeout(schedTimer); schedTimer=setTimeout(doSchedule,400); }

function buildReminderList(now){
  const r=plan.reminders||DEFAULT_REMINDERS;
  const stop=Math.max(0,Number(r.stopAfterMin)||0);
  const list=[]; let lastDay=null;
  if(!r.enabled) return {list,lastDay};
  const today=atMidnight(now);
  for(let i=-1;i<=DAYS_AHEAD;i++){
    const day=addDays(today,i);
    if(day<START()||day>END()) continue;
    const dk=ymd(day);
    for(const m of sortedMeds()){
      if(m.remind===false || !/^\d{2}:\d{2}$/.test(m.time||"")) continue;
      const s=scheduled(m,day); if(!s || s.kind==="none") continue;
      const e=logs[dk+"_"+m.id]; if(e && e.status) continue;
      const [hh,mm]=m.time.split(":").map(Number);
      const base=new Date(day.getFullYear(),day.getMonth(),day.getDate(),hh,mm,0,0);
      const name=tr(m.name)||t("medicine");
      const unknown=s.kind==="unknown";
      const stopMin=unknown?0:stop;
      if(base.getTime()+stopMin*60000 < now.getTime()-60000) continue;
      const dose=unknown?t("al_no_dose"):doseText(s.text);
      const slot=tr(m.slotLabel), ins=tr(m.instrNote);
      list.push({key:dk+"_"+m.id, base:base.getTime(), stopMin,
        title: unknown?t("al_confirm_title",{name}):(name+" "+dose),
        name, dose,
        body: unknown?t("al_confirm_body",{name}):((slot?slot+". ":"")+(ins?ins+". ":"")+t("al_body_end")),
        due: m.time, canSkip: !unknown});
      if(!lastDay || day>lastDay) lastDay=day;
    }
  }
  return {list,lastDay};
}

function nativeLabels(){
  const keys=Object.keys(STR.en).filter(k=>k.startsWith("n_"));
  const o={}; keys.forEach(k=>o[k.slice(2)]=t(k)); return o;
}

// Entries from the alert screen and the notification: actions (taken/skip/snooze) and notes.
function applyAction(act){
  if(!act || !act.key || act.key.length<12) return false;
  const dk=act.key.slice(0,10), med=act.key.slice(11);
  const at=Number(act.at)||Date.now();
  if(act.kind==="note"){
    if(act.id && events.some(e=>(e.noteIds||[]).includes(act.id) || e.id===act.id)) return false;
    const target=act.ref?events.find(e=>e.id===act.ref):null;
    if(target){
      if(act.text) target.note=(target.note?target.note+"\n":"")+act.text;
      if(act.audio){ target.audio=act.audio; target.audioMs=act.audioMs||0; }
      target.noteIds=(target.noteIds||[]).concat(act.id?[act.id]:[]);
      persistEvents();
    }else{
      addEvent({id:act.id,key:act.key,date:dk,med,action:"note",at,time:act.time||"",dose:act.dose||"",note:act.text||"",audio:act.audio||"",audioMs:act.audioMs||0,source:act.source||"alert"});
    }
    return true;
  }
  const action=act.action;
  if(!["taken","skip","snooze"].includes(action)) return false;
  if(act.id && events.some(e=>e.id===act.id)) return false;
  addEvent({id:act.id,key:act.key,date:dk,med,action,at,time:act.time||"",dose:act.dose||"",note:"",source:act.source||"alert"});
  if(action==="snooze") return true;
  const status=action==="taken"?"Taken":"Skipped";
  const cur=Object.assign({status:"",time:"",note:""},logs[act.key]||{});
  cur.status=status; if(status==="Taken" && !cur.time) cur.time=act.time||"";
  logs[act.key]=cur; persistLogs();
  return true;
}

async function takeNativeActions(){
  if(!NATIVE) return;
  try{
    const r=await DoseAlarm.takeActions();
    let changed=false;
    (r.actions||[]).forEach(a=>{ if(applyAction(a)) changed=true; });
    if(changed && !draft) render();
  }catch(_){}
}

async function doSchedule(){
  await takeNativeActions();
  const now=new Date();
  const {list,lastDay}=buildReminderList(now);
  remindState.count=list.length; remindState.until=lastDay; remindState.error="";
  if(NATIVE){
    try{ await DoseAlarm.setAlarms({alarms:list, repeatMin:Math.max(1,Number((plan.reminders||{}).repeatMin)||10), lang:ui.lang, elderly:ui.elderly, labels:nativeLabels()}); }
    catch(e){ remindState.error=String(e&&e.message||e); }
  }
  renderRemindBox();
}

async function setupNotifications(){
  if(!NATIVE) return;
  // Build 1 used standard notifications; clear any that are still queued.
  try{
    const p=await LocalNotifications.getPending();
    if(p.notifications.length) await LocalNotifications.cancel({notifications:p.notifications.map(n=>({id:n.id}))});
  }catch(_){}
  try{
    await DoseAlarm.addListener("doseAction", a=>{ if(applyAction(a)){ if(!draft) render(); scheduleReminders(); } });
  }catch(_){}
}

function renderRemindBox(){
  const box=$("remindBox"); box.textContent=""; box.className="remind";
  const r=plan.reminders||{};
  const add=(html)=>{const s=document.createElement("span");s.innerHTML=html;box.appendChild(s);return s};
  const btn=(txt,fn)=>{const b=document.createElement("button");b.className="btn";b.type="button";b.textContent=txt;b.addEventListener("click",fn);box.appendChild(b)};
  const vt=$("versionText"); if(vt) vt.textContent=NATIVE?t("version",{v:(remindState.version||"?")}):"";
  box.hidden=false;
  if(!NATIVE){ add(escapeHtml(t("rb_web"))); return; }
  if(!r.enabled){ add(t("rb_off_html")); return; }
  const after=async()=>{ await refreshPermissions(); renderRemindBox(); };
  if(!remindState.notifications){
    box.classList.add("bad"); add(t("rb_notif_html"));
    btn(t("allow_notif"),async()=>{try{await LocalNotifications.requestPermissions();}catch(_){} await after(); if(!remindState.notifications){ try{await DoseAlarm.openFullScreenSettings();}catch(_){} }});
    return;
  }
  const missing=[];
  if(!remindState.overlay) missing.push([t("allow_overlay"),t("rb_overlay"),()=>DoseAlarm.openOverlaySettings()]);
  if(!remindState.fullScreen) missing.push([t("allow_fs"),t("rb_fs"),()=>DoseAlarm.openFullScreenSettings()]);
  if(missing.length){
    box.classList.add("bad");
    missing.forEach(([label,txt,fn])=>{ add("<b>"+escapeHtml(txt)+"</b>"); btn(label,async()=>{try{await fn();}catch(_){} }); });
    return;
  }
  if(!remindState.exact){ box.classList.add("bad"); add(t("rb_exact_html")); return; }
  if(remindState.error){ box.classList.add("bad"); add(t("rb_error_html",{e:escapeHtml(remindState.error)})); btn(t("try_again"),scheduleReminders); return; }
  const until=remindState.until?remindState.until.toLocaleDateString(LOCALE(),{weekday:"short",day:"numeric",month:"short"}):null;
  const rep=Number(r.repeatMin)||10;
  add(t("rb_on_html",{n:rep})+escapeHtml(until?t("rb_until",{d:until}):t("rb_none")));
}
function escapeHtml(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]))}

/* ---------- Plan editor ---------- */
let draft=null, edErrors=[], confirmAction=null;

function el(tag,props,kids){const e=document.createElement(tag);if(props)for(const k in props){if(k==="text")e.textContent=props[k];else e.setAttribute(k,props[k]);}(kids||[]).forEach(c=>c&&e.appendChild(c));return e}
function textField(id,label,value,onInput,type,extraCls){
  const inp=el("input",{type:type||"text",id:id}); if(extraCls) inp.className=extraCls; inp.value=value==null?"":value;
  inp.addEventListener("input",()=>onInput(inp.value)); inp.addEventListener("change",()=>onInput(inp.value));
  return el("div",{class:"field"},[el("label",{for:id,text:label}),inp]);
}
function selectField(id,label,options,value,onChange){
  const s=el("select",{id:id}); options.forEach(([v,tx])=>{const o=el("option",{value:v,text:tx}); if(v===value)o.selected=true; s.appendChild(o)});
  s.addEventListener("change",()=>onChange(s.value));
  return el("div",{class:"field"},[el("label",{for:id,text:label}),s]);
}
function checkField(id,label,checked,onChange){
  const cb=el("input",{type:"checkbox",id:id}); cb.checked=!!checked; cb.addEventListener("change",()=>onChange(cb.checked));
  return el("label",{class:"check",for:id},[cb,document.createTextNode(label)]);
}

function openEditor(){
  draft=clone(plan); edErrors=[]; confirmAction=null;
  $("trackView").hidden=true; $("editView").hidden=false; $("editBtn").hidden=true; $("settingsRow").hidden=true;
  renderEditor(); window.scrollTo(0,0);
}
function closeEditor(){
  $("editView").hidden=true; $("trackView").hidden=false; $("editBtn").hidden=false; $("settingsRow").hidden=false;
  draft=null; render();
}

function renderEditor(){
  const COLORS=COLOR_IDS.map(c=>[c,t("col_"+c)]), SOURCES=SOURCE_IDS.map(s=>[s,t("src_"+s)]), PATTERNS=PATTERN_IDS.map(p=>[p,t("pat_"+p)]);
  const v=$("editView"); v.textContent="";
  v.appendChild(el("h2",{text:t("ed_title"),style:"margin:0"}));
  v.appendChild(el("p",{class:"ed-intro",text:t("ed_intro")}));
  if(t("ed_data_note")) v.appendChild(el("p",{class:"ed-intro",text:t("ed_data_note")}));

  const rem=el("div",{class:"ed-med",style:"--c:var(--slate)"});
  rem.appendChild(el("h3",{text:t("ed_reminders")}));
  rem.appendChild(checkField("rem-on",t("ed_rem_on"),draft.reminders.enabled,x=>draft.reminders.enabled=x));
  rem.appendChild(el("div",{class:"grid2"},[
    textField("rem-rep",t("ed_rep"),draft.reminders.repeatMin,x=>draft.reminders.repeatMin=x,"number","num"),
    textField("rem-stop",t("ed_stop"),draft.reminders.stopAfterMin,x=>draft.reminders.stopAfterMin=x,"number","num")]));
  rem.appendChild(el("p",{class:"ed-intro",text:t("ed_rem_intro")}));
  v.appendChild(rem);

  const cov=el("div",{class:"ed-med",style:"--c:var(--slate)"});
  cov.appendChild(el("h3",{text:t("ed_cov")}));
  cov.appendChild(el("div",{class:"grid2"},[
    textField("cov-s",t("ed_first"),draft.start,x=>draft.start=x,"date"),
    textField("cov-e",t("ed_last"),draft.end,x=>draft.end=x,"date")]));
  v.appendChild(cov);

  draft.meds.forEach((m,mi)=>{
    const box=el("div",{class:"ed-med"}); box.style.setProperty("--c",cvar(m.color));
    box.appendChild(el("h3",{text:tr(m.name)||t("ed_newmed")}));
    const p="m"+mi+"-";
    box.appendChild(el("div",{class:"grid2"},[
      textField(p+"name",t("ed_name"),m.name,x=>m.name=x),
      textField(p+"gen",t("ed_gen"),m.generic,x=>m.generic=x),
      textField(p+"time",t("ed_time"),m.time,x=>m.time=x,"time"),
      textField(p+"slot",t("ed_slot"),m.slotLabel,x=>m.slotLabel=x),
      selectField(p+"col",t("ed_color"),COLORS,m.color,x=>{m.color=x;renderEditor()})
    ]));
    box.appendChild(checkField(p+"rem",t("ed_remmed"),m.remind!==false,x=>m.remind=x));
    box.appendChild(el("div",{class:"grid2"},[
      textField(p+"ins",t("ed_ins"),m.instrNote,x=>m.instrNote=x),
      textField(p+"pref",t("ed_pref"),m.prefNote,x=>m.prefNote=x),
      textField(p+"open",t("ed_open"),m.openNote,x=>m.openNote=x)
    ]));
    box.appendChild(checkField(p+"tc",t("ed_tc"),m.timeConfirmed,x=>m.timeConfirmed=x));

    const ph=el("div",{class:"phases"});
    ph.appendChild(el("div",{class:"lbl",text:t("ed_steps")}));
    m.phases.forEach((s,si)=>{
      const q=p+"p"+si+"-";
      const pb=el("div",{class:"phase"});
      const del=el("button",{type:"button",class:"btn small danger",text:t("ed_remove_step")});
      del.addEventListener("click",()=>{m.phases.splice(si,1);renderEditor()});
      pb.appendChild(el("div",{class:"phase-h"},[el("span",{text:t("ed_step",{n:si+1})}),del]));
      const fields=[
        textField(q+"s",t("ed_from"),s.start,x=>s.start=x,"date"),
        textField(q+"e",t("ed_to"),s.end,x=>s.end=x,"date"),
        selectField(q+"pat",t("ed_pattern"),PATTERNS,s.pattern,x=>{s.pattern=x; if(x==="alternate"&&s.doses.length<2)s.doses.push(""); if(x!=="alternate")s.doses=[s.doses[0]||""]; renderEditor()})
      ];
      if(s.pattern==="alternate"){
        fields.push(textField(q+"d0",t("ed_d0alt"),s.doses[0],x=>s.doses[0]=x));
        fields.push(textField(q+"d1",t("ed_d1alt"),s.doses[1],x=>s.doses[1]=x));
      }else if(s.pattern==="confirm"){
        fields.push(textField(q+"d0",t("ed_dconfirm"),s.doses[0],x=>s.doses[0]=x));
      }else{
        fields.push(textField(q+"d0",t("ed_dose"),s.doses[0],x=>s.doses[0]=x));
      }
      fields.push(selectField(q+"src",t("ed_source"),SOURCES,s.source||"reported",x=>s.source=x));
      pb.appendChild(el("div",{class:"grid2"},fields));
      pb.appendChild(textField(q+"n",t("ed_note"),s.note,x=>s.note=x));
      ph.appendChild(pb);
    });
    const addStep=el("button",{type:"button",class:"btn small",text:t("ed_add_step")});
    addStep.addEventListener("click",()=>{
      const last=m.phases[m.phases.length-1];
      let start=draft.start; if(last&&last.end){const d=parseYmd(last.end); if(d) start=ymd(addDays(d,1));}
      m.phases.push({start:start,end:"",pattern:"daily",doses:[""],note:"",source:"reported"}); renderEditor();
    });
    ph.appendChild(addStep);
    box.appendChild(ph);

    if(confirmAction && confirmAction.type==="removeMed" && confirmAction.index===mi){
      const yes=el("button",{type:"button",class:"btn small danger",text:t("ed_remove")}); yes.addEventListener("click",()=>{draft.meds.splice(mi,1);confirmAction=null;renderEditor()});
      const no=el("button",{type:"button",class:"btn small",text:t("ed_keep")}); no.addEventListener("click",()=>{confirmAction=null;renderEditor()});
      box.appendChild(el("div",{class:"confirmbox"},[el("span",{text:t("ed_remove_q",{name:tr(m.name)||t("ed_this_med")})}),yes,no]));
    }else{
      const rm=el("button",{type:"button",class:"btn small danger",text:t("ed_remove_med")}); rm.addEventListener("click",()=>{confirmAction={type:"removeMed",index:mi};renderEditor()});
      box.appendChild(el("div",{class:"row"},[rm]));
    }
    v.appendChild(box);
  });

  const addMed=el("button",{type:"button",class:"btn",text:t("ed_add_med")});
  addMed.addEventListener("click",()=>{ const m=newMed(); m.phases[0].start=draft.start; draft.meds.push(m); renderEditor(); });
  v.appendChild(el("div",{class:"row"},[addMed]));

  if(edErrors.length){const ul=el("ul",{class:"err"}); edErrors.forEach(x=>ul.appendChild(el("li",{text:x}))); v.appendChild(ul)}

  const bar=el("div",{class:"bar"});
  const saveB=el("button",{type:"button",class:"btn primary",text:t("ed_save")}); saveB.addEventListener("click",savePlan);
  const cancel=el("button",{type:"button",class:"btn",text:t("ed_cancel")}); cancel.addEventListener("click",closeEditor);
  bar.append(saveB,cancel);
  v.appendChild(bar);
}

function validate(p){
  const errs=[];
  const s=parseYmd(p.start), e=parseYmd(p.end);
  if(!s||!e) errs.push(t("v_cov_need"));
  else if(e<s) errs.push(t("v_cov_order"));
  else if(daysBetween(s,e)>730) errs.push(t("v_cov_long"));
  const rep=Number(p.reminders.repeatMin), stop=Number(p.reminders.stopAfterMin);
  if(!(rep>=1&&rep<=240)) errs.push(t("v_rep"));
  if(!(stop>=0&&stop<=720)) errs.push(t("v_stop"));
  if(!p.meds.length) errs.push(t("v_nomed"));
  p.meds.forEach((m,i)=>{
    const nm=tr(m.name.trim())||t("v_medn",{n:i+1});
    if(!m.name.trim()) errs.push(t("v_name",{m:nm}));
    if(m.remind!==false && p.reminders.enabled && !/^\d{2}:\d{2}$/.test(m.time||"")) errs.push(t("v_time",{m:nm}));
    if(!m.phases.length) errs.push(t("v_nosteps",{m:nm}));
    m.phases.forEach((ph,j)=>{
      const a=parseYmd(ph.start), b=ph.end?parseYmd(ph.end):null, n=j+1;
      if(!a) errs.push(t("v_start",{m:nm,n}));
      if(ph.end && !b) errs.push(t("v_endinv",{m:nm,n}));
      if(a&&b&&b<a) errs.push(t("v_endbefore",{m:nm,n}));
      if(ph.pattern!=="confirm" && !(ph.doses[0]||"").trim()) errs.push(t("v_dose",{m:nm,n}));
      if(ph.pattern==="alternate" && !(ph.doses[1]||"").trim()) errs.push(t("v_dose2",{m:nm,n}));
    });
    for(let j=0;j<m.phases.length;j++)for(let k=j+1;k<m.phases.length;k++){
      const A=m.phases[j],B=m.phases[k];
      const as=parseYmd(A.start),ae=A.end?parseYmd(A.end):new Date(9e15),bs=parseYmd(B.start),be=B.end?parseYmd(B.end):new Date(9e15);
      if(as&&bs&&as<=be&&bs<=ae) errs.push(t("v_overlap",{m:nm,a:j+1,b:k+1}));
    }
  });
  return errs;
}

async function savePlan(){
  draft.meds.forEach(m=>{m.name=m.name.trim();m.phases.sort((a,b)=>(a.start||"").localeCompare(b.start||""))});
  draft.reminders.repeatMin=Number(draft.reminders.repeatMin); draft.reminders.stopAfterMin=Number(draft.reminders.stopAfterMin);
  edErrors=validate(draft);
  if(edErrors.length){renderEditor(); const er=document.querySelector("#editView .err"); if(er) er.scrollIntoView({block:"center"}); return;}
  plan=clone(draft);
  await persistPlan();
  closeEditor(); saved();
  scheduleReminders();
}

/* ---------- My Notes: summary, timeline, filters, physician report ---------- */
let notesOpen=false;
const nv={period:"week", from:"", to:"", med:"all", action:"all", notesOnly:false, step:null, lang:null, busy:false, msg:""};
let audioEl=null, playingId=null;

function openNotes(){
  notesOpen=true; nv.step=null; nv.msg="";
  $("trackView").hidden=true; $("notesView").hidden=false; $("editBtn").hidden=true; $("settingsRow").hidden=true;
  renderNotes(); window.scrollTo(0,0);
}
function closeNotes(){
  stopAudio(); notesOpen=false;
  $("notesView").hidden=true; $("trackView").hidden=false; $("editBtn").hidden=false; $("settingsRow").hidden=false;
  render();
}

function periodRange(){
  const today=atMidnight(new Date());
  let a,b=today;
  if(nv.period==="week") a=weekStart(today);
  else if(nv.period==="two") a=addDays(today,-13);
  else if(nv.period==="all") a=START();
  else { a=parseYmd(nv.from)||addDays(today,-6); b=parseYmd(nv.to)||today; }
  if(b<a){ const x=a; a=b; b=x; }
  return {a,b};
}
const inRange=(dk,a,b)=>{ const d=parseYmd(dk); return d && d>=a && d<=b; };
const medOk=(id,f)=>f==="all"||id===f;
const fmtDate=d=>d.toLocaleDateString(LOCALE(),{weekday:"short",day:"numeric",month:"short"});
const fmtDur=ms=>{ const s=Math.round((ms||0)/1000); return Math.floor(s/60)+":"+String(s%60).padStart(2,"0"); };
const actionWord=a=>t({taken:"a_taken",skip:"a_skip",snooze:"a_snooze",note:"a_note"}[a]||"a_note");
const medById=id=>plan.meds.find(m=>m.id===id);

// Timeline: recorded events plus older log entries made before notes existed.
function timeline(a,b,medF){
  const items=events.filter(e=>inRange(e.date,a,b)&&medOk(e.med,medF)).map(e=>Object.assign({},e));
  for(const k in logs){
    const dk=k.slice(0,10), med=k.slice(11), L=logs[k];
    if(!inRange(dk,a,b)||!medOk(med,medF)) continue;
    const act=ACTION_OF[L.status];
    const at=(()=>{ const d=parseYmd(dk); if(/^\d{2}:\d{2}$/.test(L.time||"")){ const [h,m]=L.time.split(":").map(Number); d.setHours(h,m); } else d.setHours(23,59); return d.getTime(); })();
    const m=medById(med); const s=m?scheduled(m,parseYmd(dk)):null;
    if(act && !events.some(e=>e.key===k&&e.action===act))
      items.push({id:"log_"+k,key:k,date:dk,med,action:act,at,time:L.time||"",dose:s?s.text:"",note:L.note||"",source:"log"});
    else if(L.note)
      items.push({id:"lognote_"+k,key:k,date:dk,med,action:"note",at,time:L.time||"",dose:s?s.text:"",note:L.note,source:"log"});
  }
  return items.sort((x,y)=>y.at-x.at);
}

function summary(a,b,medF){
  const now=new Date(), today=atMidnight(now), rows=[];
  const end=b<today?b:today;
  for(const m of sortedMeds()){
    if(!medOk(m.id,medF)) continue;
    const r={med:m,taken:0,skipped:0,open:0,snoozeDoses:0,snoozePress:0};
    for(let d=new Date(a); d<=end; d=addDays(d,1)){
      if(d<START()||d>END()) continue;
      const s=scheduled(m,d); if(!s||s.kind!=="dose") continue;
      const L=logs[ymd(d)+"_"+m.id];
      if(L&&L.status==="Taken") r.taken++;
      else if(L&&L.status==="Skipped") r.skipped++;
      else if(!(L&&L.status)){
        let due=true;
        if(ymd(d)===ymd(today) && /^\d{2}:\d{2}$/.test(m.time||"")){ const [h,mi]=m.time.split(":").map(Number); due=now.getHours()*60+now.getMinutes()>=h*60+mi; }
        if(due) r.open++;
      }
    }
    const sn=events.filter(e=>e.med===m.id&&e.action==="snooze"&&inRange(e.date,a,b));
    r.snoozePress=sn.length; r.snoozeDoses=new Set(sn.map(e=>e.key)).size;
    rows.push(r);
  }
  return rows;
}

function stopAudio(){ if(audioEl){ try{audioEl.pause();}catch(_){} } playingId=null; }
function toggleAudio(item,btn){
  if(playingId===item.id){ stopAudio(); btn.textContent=t("mn_play"); return; }
  stopAudio();
  document.querySelectorAll(".playbtn").forEach(b=>b.textContent=t("mn_play"));
  const src=NATIVE?Capacitor.convertFileSrc("file://"+item.audio):item.audio;
  audioEl=audioEl||new Audio();
  audioEl.src=src; audioEl.onended=()=>{ playingId=null; btn.textContent=t("mn_play"); };
  audioEl.onerror=()=>{ playingId=null; btn.textContent=t("mn_audio_missing"); };
  audioEl.play().then(()=>{ playingId=item.id; btn.textContent=t("mn_stop"); }).catch(()=>{ btn.textContent=t("mn_audio_missing"); });
}

function renderNotes(){
  const v=$("notesView"); v.textContent="";
  const head=el("div",{class:"nhead"});
  const back=el("button",{type:"button",class:"btn",text:t("back")}); back.addEventListener("click",()=>{ if(nv.step){ nv.step=null; renderNotes(); } else closeNotes(); });
  head.append(el("h2",{text:t("mn_title"),style:"margin:0"}),back);
  v.appendChild(head);
  if(nv.step){ renderShareStep(v); return; }

  // Filters
  const f=el("div",{class:"ed-med filters",style:"--c:var(--slate)"});
  const periods=[["week","p_week"],["two","p_two"],["all","p_all"],["custom","p_custom"]].map(([k,l])=>[k,t(l)]);
  const g=el("div",{class:"grid2"});
  g.appendChild(selectField("nf-period",t("mn_period"),periods,nv.period,x=>{nv.period=x; if(x==="custom"&&!nv.from){ nv.from=ymd(addDays(new Date(),-6)); nv.to=ymd(new Date()); } renderNotes();}));
  g.appendChild(selectField("nf-med",t("mn_med"),[["all",t("mn_all_meds")]].concat(sortedMeds().map(m=>[m.id,medName(m)])),nv.med,x=>{nv.med=x;renderNotes();}));
  g.appendChild(selectField("nf-act",t("mn_action"),[["all",t("a_all")],["taken",t("a_taken")],["skip",t("a_skip")],["snooze",t("a_snooze")],["note",t("a_note")]],nv.action,x=>{nv.action=x;renderNotes();}));
  if(nv.period==="custom"){
    g.appendChild(textField("nf-from",t("mn_from"),nv.from,x=>{nv.from=x;},"date"));
    g.appendChild(textField("nf-to",t("mn_to"),nv.to,x=>{nv.to=x;},"date"));
    ["nf-from","nf-to"].forEach(id=>setTimeout(()=>{ const i=$(id); if(i) i.addEventListener("change",()=>renderNotes()); },0));
  }
  f.appendChild(g);
  f.appendChild(checkField("nf-notes",t("mn_notes_only"),nv.notesOnly,x=>{nv.notesOnly=x;renderNotes();}));
  v.appendChild(f);

  const {a,b}=periodRange();
  v.appendChild(el("p",{class:"sub",text:fmtShort(a)+" – "+fmtShort(b)}));

  // Share
  const share=el("button",{type:"button",class:"btn primary bigbtn",text:t("mn_share")});
  share.addEventListener("click",()=>{ nv.step="lang"; nv.msg=""; renderNotes(); window.scrollTo(0,0); });
  v.appendChild(share);
  if(nv.msg) v.appendChild(el("p",{class:"offline",text:nv.msg}));

  // Summary
  v.appendChild(el("h3",{text:t("mn_summary"),class:"nsec"}));
  const rows=summary(a,b,nv.med);
  const tw=el("div",{class:"tablewrap"});
  const tb=el("table",{class:"sumtable"});
  const hr=el("tr"); [t("sum_med"),t("sum_taken"),t("sum_skipped"),t("sum_snoozed"),t("sum_open")].forEach(h=>hr.appendChild(el("th",{text:h}))); tb.appendChild(hr);
  rows.forEach(r=>{ const tr_=el("tr"); const c0=el("td",{text:medName(r.med)}); c0.style.setProperty("--c",cvar(r.med.color)); c0.className="medcell";
    tr_.append(c0,el("td",{text:String(r.taken)}),el("td",{text:String(r.skipped)}),el("td",{text:r.snoozePress?t("sum_snooze_detail",{d:r.snoozeDoses,p:r.snoozePress}):"0"}),el("td",{text:String(r.open)})); tb.appendChild(tr_); });
  tw.appendChild(tb); v.appendChild(tw);
  v.appendChild(el("p",{class:"foot",text:t("sum_note")}));

  // Timeline
  v.appendChild(el("h3",{text:t("mn_timeline"),class:"nsec"}));
  let items=timeline(a,b,nv.med);
  if(nv.action!=="all") items=items.filter(x=>x.action===nv.action);
  if(nv.notesOnly) items=items.filter(x=>x.note||x.audio);
  if(!items.length){ v.appendChild(el("p",{class:"foot",text:t("mn_empty")})); return; }
  const list=el("div",{class:"tl"});
  items.forEach(x=>{
    const m=medById(x.med);
    const card=el("article",{class:"tlcard"}); card.style.setProperty("--c",cvar(m&&m.color));
    const top=el("div",{class:"tltop"});
    top.append(el("span",{class:"tlwhen",text:fmtDate(parseYmd(x.date))+(x.time?" · "+x.time:"")}),el("span",{class:"badge act-"+x.action,text:actionWord(x.action)}));
    card.appendChild(top);
    card.appendChild(el("div",{class:"tlmed",text:(m?medName(m):x.med)+(x.dose?"  "+doseText(x.dose):"")}));
    if(x.note) card.appendChild(el("div",{class:"tlnote",text:x.note}));
    if(x.audio){
      const row=el("div",{class:"row"});
      const pb=el("button",{type:"button",class:"btn playbtn",text:playingId===x.id?t("mn_stop"):t("mn_play")});
      pb.addEventListener("click",()=>toggleAudio(x,pb));
      row.append(pb,el("span",{class:"foot",text:t("mn_voice",{d:fmtDur(x.audioMs)})}));
      card.appendChild(row);
    }
    if(x.source==="panel") card.appendChild(el("div",{class:"foot",text:t("mn_from_panel")}));
    list.appendChild(card);
  });
  v.appendChild(list);
}

// One choice per screen: language, then (if there are voice notes) PDF or ZIP.
function renderShareStep(v){
  const {a,b}=periodRange();
  const box=el("div",{class:"ed-med stepbox",style:"--c:var(--slate)"});
  if(nv.busy){ box.appendChild(el("h3",{text:t("sh_making")})); v.appendChild(box); return; }
  const go=async(zip)=>{
    if(!NATIVE){ nv.step=null; nv.msg=t("sh_web"); renderNotes(); return; }
    nv.busy=true; renderNotes();
    try{
      const r=buildReport(nv.lang,a,b,nv.med,zip);
      await DoseAlarm.shareReport({blocks:r.blocks, rtl:nv.lang==="ar", zip, fileBase:r.fileBase, audio:r.audio, pageLabel:r.pageLabel, chooserTitle:r.chooser});
      nv.busy=false; nv.step=null; nv.msg=""; renderNotes(); saved("sh_ready");
    }catch(e){ nv.busy=false; nv.step=null; nv.msg=t("sh_fail",{e:(e&&e.message||e)}); renderNotes(); }
  };
  if(nv.step==="lang"){
    box.appendChild(el("h3",{text:t("sh_lang_q")}));
    [["en","sh_en"],["ar","sh_ar"]].forEach(([l,k])=>{
      const bt=el("button",{type:"button",class:"btn bigbtn",text:t(k)});
      bt.addEventListener("click",()=>{ nv.lang=l; const voice=timeline(a,b,nv.med).filter(x=>x.audio).length; if(voice){ nv.step="zip"; renderNotes(); } else go(false); });
      box.appendChild(bt);
    });
  }else if(nv.step==="zip"){
    const voice=timeline(a,b,nv.med).filter(x=>x.audio).length;
    box.appendChild(el("h3",{text:t("sh_zip_q")}));
    box.appendChild(el("p",{class:"ed-intro",text:t("sh_zip_hint",{n:voice})}));
    const p1=el("button",{type:"button",class:"btn bigbtn",text:t("sh_pdf_only")}); p1.addEventListener("click",()=>go(false));
    const p2=el("button",{type:"button",class:"btn bigbtn",text:t("sh_zip")}); p2.addEventListener("click",()=>go(true));
    box.append(p1,p2);
  }
  v.appendChild(box);
}

// Builds the report in the chosen language as simple blocks; the phone draws them into a PDF.
function buildReport(lang,a,b,medF,withAudio){
  const keep=ui.lang; ui.lang=lang;
  try{
    const B=[]; const add=(t_,text,extra)=>B.push(Object.assign({t:t_,text},extra||{}));
    const now=new Date();
    add("h1",t("rp_title"));
    add("p",t("rp_period",{a:fmtShort(a),b:fmtShort(b)}),{bold:true});
    add("small",t("rp_created",{d:fmtShort(now)+" "+hhmm(now)}));
    add("small",t("rp_disclaimer"));

    add("h2",t("rp_meds"));
    for(const m of sortedMeds()){
      if(!medOk(m.id,medF)) continue;
      const head=medName(m)+(m.generic?" ("+tr(m.generic)+")":"")+(m.time?" · "+t("rp_time",{t:m.time})+(m.slotLabel?" ("+tr(m.slotLabel)+")":""):"");
      add("p",head,{bold:true});
      for(const ph of m.phases){
        const ps=parseYmd(ph.start), pe=ph.end?parseYmd(ph.end):null;
        if(!ps || ps>b || (pe && pe<a)) continue;
        const range=fmtShort(ps)+" – "+(pe?fmtShort(pe):t("rp_ongoing"));
        let what;
        if(ph.pattern==="confirm") what=t("rp_confirm");
        else if(ph.pattern==="alternate") what=t("rp_alt",{a:doseText((ph.doses[0]||"").trim())||"?",b:isNoDose(ph.doses[1])?t("no_dose"):doseText((ph.doses[1]||"").trim())});
        else what=t("rp_daily",{d:doseText((ph.doses[0]||"").trim())});
        const src=ph.pattern==="confirm"?"unconfirmed":(ph.source||"reported");
        add("li",range+": "+what+" — "+t("rp_src_"+src)+(ph.note?". "+tr(ph.note):""));
      }
      if(m.openNote) add("li",t("tag_unc")+": "+tr(m.openNote));
    }

    add("h2",t("rp_summary"));
    const rows=summary(a,b,medF);
    add("table","",{cols:[t("sum_med"),t("sum_taken"),t("sum_skipped"),t("sum_snoozed"),t("sum_open")],widths:[.34,.14,.14,.24,.14],
      rows:rows.map(r=>[medName(r.med),String(r.taken),String(r.skipped),r.snoozePress?t("sum_snooze_detail",{d:r.snoozeDoses,p:r.snoozePress}):"0",String(r.open)])});
    add("small",t("sum_note"));

    add("h2",t("rp_notes"));
    const items=timeline(a,b,medF).sort((x,y)=>x.at-y.at);
    const audio=[];
    if(!items.length) add("p",t("rp_no_entries"));
    else add("table","",{cols:[t("rp_col_when"),t("rp_col_med"),t("rp_col_dose"),t("rp_col_action"),t("rp_col_note")],widths:[.19,.17,.12,.12,.40],
      rows:items.map(x=>{
        const m=medById(x.med);
        let note=x.note||"";
        if(x.audio){
          const name=x.date+"_"+(x.time||"").replace(":","")+"_"+x.med+"_"+(audio.length+1)+".m4a";
          if(withAudio) audio.push({path:x.audio,name});
          const v=t("rp_voice",{d:fmtDur(x.audioMs)})+" — "+(withAudio?t("rp_file",{f:name}):t("rp_not_attached"));
          note=note?note+"\n"+v:v;
        }
        return [fmtShort(parseYmd(x.date))+(x.time?" "+x.time:""),m?medName(m):x.med,x.dose?doseText(x.dose):"",actionWord(x.action),note];
      })});

    if(questions.length){
      add("h2",t("rp_questions"));
      questions.forEach(q=>add("li",q));
    }
    add("small",t("rp_footer"));

    return {blocks:B, audio, fileBase:"Jadwal-Report_"+ymd(a)+"_to_"+ymd(b), pageLabel:t("rp_page"), chooser:t("sh_chooser")};
  } finally { ui.lang=keep; }
}

/* ---------- Backup and restore ---------- */
let backupOpen=false, lastBackup=null;
const bk={step:"home", password:"", data:null, msg:"", msgBad:false, safety:null, busyKey:""};
const BACKUP_REMIND_DAYS=14;

function snapshot(){
  // "med-tracker-backup" is the backup format id (kept so older backups still restore).
  return {format:"med-tracker-backup", version:1, created:new Date().toISOString(), appVersion:remindState.version||"",
    data:{plan, planIsCustom:true, logs, events, ui, questions}};
}
const audioPaths=()=>Array.from(new Set(events.filter(e=>e.audio).map(e=>e.audio)));
function backupSummary(b){
  const d=(b&&b.data)||{}, ev=Array.isArray(d.events)?d.events:[], lg=d.logs||{};
  const p=d.plan||{meds:[]};
  return {created:b.created?new Date(b.created):null, meds:(p.meds||[]).length, logs:Object.keys(lg).length,
    notes:ev.filter(e=>e.note||e.audio).length+Object.values(lg).filter(l=>l&&l.note).length, voice:ev.filter(e=>e.audio).length};
}
// Replaces all app data with a backup. Voice-note paths are pointed at this phone's notes folder.
async function applyBackup(b, notesDir){
  const d=b.data||{};
  const base=p=>String(p).split("/").pop();
  plan=d.plan&&Array.isArray(d.plan.meds)?d.plan:makeEmptyPlan();
  if(!plan.reminders) plan.reminders={...DEFAULT_REMINDERS};
  questions=Array.isArray(d.questions)?d.questions.filter(x=>typeof x==="string"):[];
  logs=d.logs&&typeof d.logs==="object"?d.logs:{};
  events=(Array.isArray(d.events)?d.events:[]).map(e=>e.audio&&notesDir?Object.assign({},e,{audio:notesDir+"/"+base(e.audio)}):e);
  if(d.ui){ ui.lang=d.ui.lang==="ar"?"ar":"en"; ui.elderly=!!d.ui.elderly; }
  await Preferences.set({key:"logs",value:JSON.stringify(logs)});
  await Preferences.set({key:"events",value:JSON.stringify(events)});
  await Preferences.set({key:"ui",value:JSON.stringify(ui)});
  await Preferences.set({key:"questions",value:JSON.stringify(questions)});
  await Preferences.set({key:"plan",value:JSON.stringify(plan)});
  selected=null;
}

async function loadBackupInfo(){
  try{ const v=await Preferences.get({key:"lastBackup"}); lastBackup=v.value?new Date(v.value):null; }catch(_){}
}
function renderBackupNotice(){
  const n=$("backupNotice"); if(!n) return; n.textContent="";
  const days=lastBackup?Math.floor((Date.now()-lastBackup.getTime())/DAY):null;
  if(!NATIVE || backupOpen || notesOpen || draft || (days!==null && days<BACKUP_REMIND_DAYS)){ n.hidden=true; return; }
  n.hidden=false;
  n.appendChild(el("span",{text:days===null?t("bk_none_yet"):t("bk_days_ago",{n:days})}));
  const b=el("button",{type:"button",class:"btn",text:t("bk_create")});
  b.addEventListener("click",()=>{ openBackup(); bk.step="pw"; renderBackup(); });
  n.appendChild(b);
}

function openBackup(){
  backupOpen=true; bk.step="home"; bk.msg=""; bk.password=""; bk.data=null;
  $("trackView").hidden=true; $("backupView").hidden=false; $("editBtn").hidden=true; $("settingsRow").hidden=true;
  refreshSafety().then(renderBackup); renderBackup(); window.scrollTo(0,0);
}
function closeBackup(){
  backupOpen=false; bk.password=""; bk.data=null;
  $("backupView").hidden=true; $("trackView").hidden=false; $("editBtn").hidden=false; $("settingsRow").hidden=false;
  render();
}
async function refreshSafety(){ if(!NATIVE) return; try{ const r=await DoseAlarm.safetyCopyInfo(); bk.safety=r.exists?new Date(r.time):null; }catch(_){ bk.safety=null; } }
function bkMsg(key,bad,vars){ bk.msg=t(key,vars); bk.msgBad=!!bad; }

function renderBackup(){
  const v=$("backupView"); v.textContent="";
  const head=el("div",{class:"nhead"});
  const back=el("button",{type:"button",class:"btn",text:t("back")});
  back.addEventListener("click",()=>{ if(bk.step==="home"||bk.step==="busy") closeBackup(); else { bk.step="home"; bk.password=""; bk.data=null; bk.msg=""; renderBackup(); } });
  head.append(el("h2",{text:t("bk_title"),style:"margin:0"}),back);
  v.appendChild(head);
  const box=el("div",{class:"ed-med stepbox",style:"--c:var(--slate)"});
  const big=(label,fn,cls)=>{ const b=el("button",{type:"button",class:"btn bigbtn"+(cls?" "+cls:""),text:label}); b.addEventListener("click",fn); box.appendChild(b); return b; };
  const para=(txt,cls)=>box.appendChild(el("p",{class:cls||"ed-intro",text:txt}));
  if(bk.msg){ const m=el("p",{class:bk.msgBad?"err":"okmsg",text:bk.msg}); v.appendChild(m); }

  if(!NATIVE && bk.step!=="home"){ bk.step="home"; }
  switch(bk.step){
    case "busy":
      box.appendChild(el("h3",{text:t(bk.busyKey||"bk_working")}));
      break;
    case "home":
      box.appendChild(el("h3",{text:lastBackup?t("bk_last",{d:fmtShort(lastBackup)}):t("bk_none_yet")}));
      para(t("bk_explain"));
      if(!NATIVE){ para(t("bk_web")); break; }
      big(t("bk_create"),()=>{ bk.msg=""; bk.step="pw"; renderBackup(); },"primary");
      big(t("bk_restore"),startRestore);
      if(bk.safety) big(t("bk_undo",{d:fmtShort(bk.safety)}),()=>{ bk.msg=""; bk.step="undo_confirm"; renderBackup(); });
      break;
    case "pw":
      box.appendChild(el("h3",{text:t("bk_pw_q")}));
      para(t("bk_pw_hint"));
      big(t("bk_use_pw"),()=>{ bk.step="setpw"; renderBackup(); });
      big(t("bk_no_pw"),()=>{ bk.password=""; bk.step="dest"; renderBackup(); });
      break;
    case "setpw": {
      box.appendChild(el("h3",{text:t("bk_setpw")}));
      para(t("bk_pw_warn"),"warnline");
      const p1=textField("bk-p1",t("bk_pw1"),"",()=>{},"password"); const p2=textField("bk-p2",t("bk_pw2"),"",()=>{},"password");
      box.append(p1,p2);
      box.appendChild(checkField("bk-show",t("bk_show"),false,x=>{ ["bk-p1","bk-p2"].forEach(id=>{ $(id).type=x?"text":"password"; }); }));
      big(t("bk_continue"),()=>{
        const a=$("bk-p1").value, b2=$("bk-p2").value;
        if(a.length<6){ bkMsg("bk_pw_short",true); renderBackup(); return; }
        if(a!==b2){ bkMsg("bk_pw_mismatch",true); renderBackup(); return; }
        bk.password=a; bk.msg=""; bk.step="dest"; renderBackup();
      },"primary");
      break;
    }
    case "dest":
      box.appendChild(el("h3",{text:t("bk_dest_q")}));
      para(bk.password?t("bk_dest_pw"):t("bk_dest_nopw"));
      big(t("bk_save_phone"),()=>createBackup("save"));
      big(t("bk_share"),()=>createBackup("share"));
      break;
    case "restore_pw": {
      box.appendChild(el("h3",{text:t("bk_enter_pw")}));
      const p=textField("bk-rp",t("bk_pw1"),"",()=>{},"password"); box.appendChild(p);
      big(t("bk_open"),()=>openPicked($("bk-rp").value),"primary");
      break;
    }
    case "restore_confirm": {
      const s=backupSummary(bk.data);
      box.appendChild(el("h3",{text:t("bk_contains")}));
      const ul=el("ul",{class:"bklist"});
      [s.created?t("bk_s_created",{d:fmtShort(s.created)+" "+hhmm(s.created)}):null, t("bk_s_meds",{n:s.meds}), t("bk_s_logs",{n:s.logs}), t("bk_s_notes",{n:s.notes}), t("bk_s_voice",{n:s.voice})]
        .filter(Boolean).forEach(x=>ul.appendChild(el("li",{text:x})));
      box.appendChild(ul);
      para(t("bk_replace_warn"),"warnline");
      big(t("bk_replace"),()=>doRestore(true),"dangerbig");
      big(t("bk_cancel"),()=>{ bk.data=null; bk.step="home"; renderBackup(); });
      break;
    }
    case "undo_confirm":
      box.appendChild(el("h3",{text:t("bk_undo_q")}));
      para(t("bk_undo_hint",{d:bk.safety?fmtShort(bk.safety):""}));
      big(t("bk_undo_yes"),undoRestore,"dangerbig");
      big(t("bk_cancel"),()=>{ bk.step="home"; renderBackup(); });
      break;
  }
  v.appendChild(box);
}

async function createBackup(dest){
  await takeNativeActions();
  bk.busyKey="bk_making"; bk.step="busy"; renderBackup();
  const now=new Date();
  try{
    const r=await DoseAlarm.createBackup({data:JSON.stringify(snapshot()), audio:audioPaths(), password:bk.password, dest,
      fileName:"Jadwal-backup-"+ymd(now)+"_"+hhmm(now).replace(":","")+".jadwal", chooserTitle:t("bk_share_title")});
    bk.password=""; bk.step="home";
    if(r && r.ok){
      lastBackup=now; await Preferences.set({key:"lastBackup",value:now.toISOString()});
      bkMsg(dest==="save"?"bk_saved":"bk_shared"); saved(dest==="save"?"bk_saved":"bk_shared");
    }else bkMsg("bk_cancelled");
  }catch(e){ bk.password=""; bk.step="home"; bkMsg("bk_fail",true,{e:(e&&e.message||e)}); }
  renderBackup();
}

async function startRestore(){
  bk.msg="";
  try{
    const r=await DoseAlarm.pickBackup();
    if(!r || !r.picked){ renderBackup(); return; }
    if(r.encrypted){ bk.step="restore_pw"; renderBackup(); return; }
    await openPicked("");
  }catch(e){ bk.step="home"; bkMsg(e&&e.code==="not_backup"?"bk_not_backup":"bk_fail",true,{e:(e&&e.message||e)}); renderBackup(); }
}

async function openPicked(password){
  bk.busyKey="bk_opening"; bk.step="busy"; renderBackup();
  try{
    const r=await DoseAlarm.openBackup({password});
    const b=JSON.parse(r.data);
    if(!b || b.format!=="med-tracker-backup" || !b.data) throw {code:"not_backup"};
    bk.data=b; bk.msg=""; bk.step="restore_confirm";
  }catch(e){
    if(e&&e.code==="wrong_password"){ bk.step="restore_pw"; bkMsg("bk_wrong_pw",true); }
    else { bk.step="home"; bkMsg(e&&e.code==="not_backup"?"bk_not_backup":"bk_fail",true,{e:(e&&e.message||e)}); }
  }
  renderBackup();
}

async function doRestore(withSafety){
  const b=bk.data; if(!b) return;
  bk.busyKey="bk_restoring"; bk.step="busy"; renderBackup();
  try{
    await takeNativeActions();
    if(withSafety) await DoseAlarm.makeSafetyCopy({data:JSON.stringify(snapshot()), audio:audioPaths()});
    const fin=await DoseAlarm.finishRestore();
    await applyBackup(b, fin.notesDir);
    bk.data=null; bk.step="home"; await refreshSafety();
    bkMsg("bk_restored"); saved("bk_restored");
    scheduleReminders();
  }catch(e){ bk.step="home"; bkMsg("bk_fail",true,{e:(e&&e.message||e)}); }
  render(); if(backupOpen){ $("trackView").hidden=true; renderBackup(); }
}

async function undoRestore(){
  bk.busyKey="bk_restoring"; bk.step="busy"; renderBackup();
  try{
    await DoseAlarm.useSafetyCopy();
    const r=await DoseAlarm.openBackup({password:""});
    bk.data=JSON.parse(r.data);
    await doRestore(false);
  }catch(e){ bk.step="home"; bkMsg("bk_fail",true,{e:(e&&e.message||e)}); renderBackup(); }
}

/* ---------- Start ---------- */
async function start(){
  $("editBtn").addEventListener("click",openEditor);
  $("notesBtn").addEventListener("click",openNotes);
  $("backupBtn").addEventListener("click",openBackup);
  $("prevWeek").addEventListener("click",()=>{selected=clampDate(addDays(weekStart(selected),-7));render()});
  $("nextWeek").addEventListener("click",()=>{selected=clampDate(addDays(weekStart(selected),7));render()});
  $("todayBtn").addEventListener("click",()=>{selected=clampDate(new Date());render()});
  $("prevDay").addEventListener("click",()=>{selected=clampDate(addDays(selected,-1));render()});
  $("nextDay").addEventListener("click",()=>{selected=clampDate(addDays(selected,1));render()});
  $("todayBtnElder").addEventListener("click",()=>{selected=clampDate(new Date());render()});
  $("langBtn").addEventListener("click",()=>{ ui.lang=AR()?"en":"ar"; persistUi(); render(); scheduleReminders(); });
  $("elderBtn").addEventListener("click",()=>{ ui.elderly=!ui.elderly; persistUi(); render(); scheduleReminders(); });

  await loadState();
  await loadBackupInfo();
  await setupNotifications();
  if(NATIVE){
    try{ const p=await LocalNotifications.checkPermissions(); if(p.display!=="granted") await LocalNotifications.requestPermissions(); }catch(_){}
  }
  await refreshPermissions();
  await takeNativeActions();
  render();
  scheduleReminders();
  $("updateBtn").addEventListener("click",()=>{ if(NATIVE) DoseAlarm.openUrl({url:RELEASES_URL}).catch(()=>{}); else window.open(RELEASES_URL,"_blank"); });

  if(NATIVE){
    App.addListener("resume", async ()=>{
      await refreshPermissions();
      await takeNativeActions();
      if(!draft && !backupOpen){ selected=clampDate(new Date()); render(); }
      scheduleReminders();
    });
  }
  setInterval(()=>{ if(!draft && isToday(selected) && !(document.activeElement && document.activeElement.matches("textarea,input"))) renderSlots(); },60000);
}
if(!NATIVE) window.__medtest={buildReminderList,applyAction,buildReport:(...a)=>buildReport(...a),snapshot:()=>snapshot(),applyBackup:(b,d)=>applyBackup(b,d),backupSummary:b=>backupSummary(b),get plan(){return plan},get logs(){return logs},get events(){return events},get ui(){return ui}};
start();
