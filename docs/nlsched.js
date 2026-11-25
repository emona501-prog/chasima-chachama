/* פענוח טקסט חופשי בעברית לשעות שימוש – "ביום ראשון פתוח מ-14:00 עד 16:00 וחסום מ-16 עד 18" */
(function(){
  const DAYS = [["ראשון","א'","יום א"],["שני","ב'","יום ב"],["שלישי","ג'","יום ג"],["רביעי","ד'","יום ד"],["חמישי","ה'","יום ה"],["שישי","ו'","יום ו"],["שבת","מוצאי שבת"]];
  const NUMW = {"אחת עשרה":11,"אחד עשרה":11,"אחד עשר":11,"שתים עשרה":12,"שתיים עשרה":12,"שתים עשר":12,"שתיים עשר":12,
    "אחת":1,"אחד":1,"שתיים":2,"שתים":2,"שלוש":3,"ארבע":4,"חמש":5,"שש":6,"שבע":7,"שמונה":8,"תשע":9,"עשר":10,"עשרה":10};

  function norm(t){
    let s = " " + t.replace(/[ְ-ׇ]/g,"").replace(/[–—]/g,"-").replace(/\s+/g," ") + " ";
    // מספרים במילים -> ספרות (ארוכים קודם)
    Object.keys(NUMW).sort((a,b)=>b.length-a.length).forEach(w=>{
      s = s.replace(new RegExp("(^|[\\sמ\\-ו])" + w + "(?=[\\s,.\\-]|$)", "g"), (m,p)=>p+NUMW[w]);
    });
    s = s.replace(/(\d{1,2})\s*וחצי/g, "$1:30").replace(/(\d{1,2})\s*ורבע/g, "$1:15")
         .replace(/רבע ל[-\s]?(\d{1,2})/g, (m,h)=>((+h+23)%24)+":45");
    return s;
  }

  // מוצאים את קטעי הימים בטקסט
  function daySegments(s){
    const marks = [];
    const add = (re, days) => { let m; const r=new RegExp(re,"g"); while((m=r.exec(s))) marks.push({i:m.index, len:m[0].length, days}); };
    add("כל (?:ה)?ימים|כל יום|כל השבוע|בכל יום|יומיום", [0,1,2,3,4,5,6]);
    add("ימי חול|(?:ראשון|א') ?(?:עד|-) ?(?:חמישי|ה')", [0,1,2,3,4]);
    add("(?:ראשון|א') ?(?:עד|-) ?(?:שישי|ו')", [0,1,2,3,4,5]);
    add("סופ\"?ש|סוף שבוע", [5,6]);
    DAYS.forEach((names,d)=>names.forEach(n=>add("(?:^|[\\sבולמ,])(?:יום )?"+n.replace(/'/g,"'")+"(?=[\\s,:.]|$)", [d])));
    // סימון ארוך גובר על קצר שבתוכו (למשל "ראשון" בתוך "ראשון עד חמישי")
    marks.sort((a,b)=>b.len-a.len);
    const clean=[];
    marks.forEach(m=>{ if(!clean.some(c=>m.i < c.i+c.len && c.i < m.i+m.len)) clean.push(m); });
    clean.sort((a,b)=>a.i-b.i);
    // ימים צמודים בלי זמנים ביניהם ("ראשון ושלישי מ-...") מתאחדים
    const segs=[];
    clean.forEach((m,k)=>{
      const next = clean[k+1];
      const text = s.slice(m.i+m.len, next? next.i : s.length);
      segs.push({days:m.days.slice(), text});
    });
    for(let k=segs.length-2;k>=0;k--){
      if(!/\d/.test(segs[k].text) && !/פתוח|חסו|נעול|סגור|מותר/.test(segs[k].text)){ segs[k+1].days = Array.from(new Set(segs[k].days.concat(segs[k+1].days))); segs[k].merged=true; }
    }
    return segs.filter(x=>!x.merged);
  }

  function period(txt){
    if(/בוקר/.test(txt)) return "am";
    if(/צהר|אחה"?צ|אחר הצהריים/.test(txt)) return "noon";
    if(/ערב/.test(txt)) return "eve";
    if(/לילה/.test(txt)) return "night";
    return "";
  }
  function to24(h, m, p){
    if(h===24) h=0;
    if(p==="am"){ if(h===12) h=0; }
    else if(p==="noon"){ if(h<12 && h<=6) h+=12; }
    else if(p==="eve"){ if(h<12) h+=12; }
    else if(p==="night"){ if(h>=6 && h<12) h+=12; else if(h===12) h=0; }
    else { if(h>=1 && h<=6) h+=12; } // בלי ציון: 1–6 = אחה"צ
    return String(h%24).padStart(2,"0")+":"+String(m).padStart(2,"0");
  }

  // טווחי שעות בתוך קטע, עם סוג: פתוח / חסום
  const PER = '(בבוקר|בצהריים|בצהרים|אחה"?צ|אחר הצהריים|בערב|בלילה)';
  function ranges(text){
    const out=[];
    const re=new RegExp('(\\d{1,2})(?:[:.](\\d{2}))?\\s*'+PER+'?\\s*(?:עד|-|ועד)\\s*(?:השעה\\s*)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*'+PER+'?','g');
    let m;
    while((m=re.exec(text))){
      const pa = period(m[3]||""), pb = period(m[6]||"");
      const p1 = pa || pb, p2 = pb || pa;
      const f = to24(+m[1], +(m[2]||0), p1), t = to24(+m[4], +(m[5]||0), p2);
      const lastIdx = (str, rx) => { let k=-1, mm; const r=new RegExp(rx.source,"g"); while((mm=r.exec(str))) k=mm.index; return k; };
      const OPEN=/פתוח|יעבוד|מותר|יפתח|לשחק|שימוש/, BLOCK=/חסו|נעול|ינעל|נעל|סגור|לא יעבוד|לא פתוח|אסור|ייחסם|יחסם/;
      const ctxB = text.slice(0, m.index);
      const bo = lastIdx(ctxB, OPEN), bb = lastIdx(ctxB, BLOCK);
      let block = bb > bo;
      const after = text.slice(m.index+m[0].length, m.index+m[0].length+15);
      if(bo<0 && bb<0) block = BLOCK.test(after) && !OPEN.test(after);
      out.push({f, t, block});
    }
    return out;
  }

  const mins = s => { const [h,m]=s.split(":").map(Number); return h*60+m; };
  const hhmm = n => String(Math.floor(n/60)%24).padStart(2,"0")+":"+String(n%60).padStart(2,"0");

  // פתוח פחות חסום -> רשימת חלונות פתוחים
  function compose(list){
    const open = list.filter(r=>!r.block), block = list.filter(r=>r.block);
    let segs = [];
    const add = (arr, r) => { let a=mins(r.f), b=mins(r.t); if(b<=a) b+=1440; arr.push([a,b]); };
    if(open.length) open.forEach(r=>add(segs,r)); else if(block.length) segs=[[0,1440]];
    const bl=[]; block.forEach(r=>add(bl,r));
    bl.slice().forEach(([a,b])=>{ if(b>1440) bl.push([a-1440,b-1440]); });
    bl.forEach(([a,b])=>{
      const next=[];
      segs.forEach(([x,y])=>{
        if(b<=x || a>=y){ next.push([x,y]); return; }
        if(a>x) next.push([x,a]);
        if(b<y) next.push([b,y]);
      });
      segs=next;
    });
    return segs.filter(([a,b])=>b-a>=5).map(([a,b])=>({f:hhmm(a%1440), t: (a===0 && b>=1440) ? "23:59" : hhmm(b%1440)}));
  }

  window.parseSchedText = function(text){
    const s = norm(text);
    let segs = daySegments(s);
    if(!segs.length) segs=[{days:[0,1,2,3,4,5,6], text:s}];
    const res = {};
    segs.forEach(seg=>{
      const rs = ranges(seg.text);
      let wins;
      if(rs.length) wins = compose(rs);
      else if(/חסו|נעול|סגור|לא פתוח|אסור/.test(seg.text)) wins = [];
      else if(/פתוח|מותר|יעבוד/.test(seg.text)) wins = [{f:"00:00", t:"23:59"}];
      else return;
      seg.days.forEach(d=>{ res[d] = wins; });
    });
    return res; // {יום: [{f,t},...]}
  };
})();
