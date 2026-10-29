(() => {
  'use strict';
  const STORAGE = 'akashic-web-demo-v1';
  const SETTINGS = 'akashic-demo-settings-v1';
  const $ = id => document.getElementById(id);
  const LOCATIONS = {
    inn:{label:'잿빛 여관',chapter:'제1장 · 잿빛 여관',alt:'창가의 비와 벽난로, 오래된 여관의 밤',connections:['road']},
    road:{label:'북쪽 숲길',chapter:'제2장 · 숲의 경계',alt:'달빛과 짙은 나무 그림자가 드리운 숲길',connections:['inn','archive']},
    archive:{label:'폐허가 된 서고',chapter:'제3장 · 이름 없는 기록',alt:'무너진 책장과 푸른 빛이 스며든 고대 서고',connections:['road']}
  };
  const newGame = () => ({version:1,location:'inn',seconds:0,turn:0,flags:{heardNorth:false,metKeeper:false,readSeal:false,bell:false,foundFragment:false,forestSeen:false},history:[],chronicle:[{title:'잿빛 여관에 도착하다',description:'비 내리는 밤, 낯선 여관의 문을 열었다.',at:0}],inventory:[{name:'빛바랜 여행 수첩',desc:'오래된 종이에 아직 빈 장이 많다.',icon:'▤'},{name:'작은 동전 주머니',desc:'하룻밤을 지낼 만한 돈이 들었다.',icon:'◈'}],draft:''});
  let game, settings = {font:100,art:true,reduced:false};
  let openWindows = [], modal = null, activeComposition = false, busy = false, historyIndex = -1, rememberedDraft='', viewLocked=false;
  let toastTimer, sceneGeneration = 0, artScene = 'inn';
  const WIN = [{id:'character',key:'C',label:'인물 정보'},{id:'inventory',key:'I',label:'소지품'},{id:'chronicle',key:'K',label:'연대기'},{id:'map',key:'M',label:'지도'},{id:'log',key:'L',label:'여정 기록'},{id:'settings',key:'Esc',label:'설정'},{id:'help',key:'?',label:'단축키 도움말'}];
  const clamp = (n,a,b)=>Math.min(b,Math.max(a,n));
  const esc = s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const getTime = sec => {
    const hour=(21+Math.floor(sec/3600))%24;
    const day=1+Math.floor((21*3600+sec)/(24*3600));
    const band= hour>=5&&hour<12?'아침':hour>=12&&hour<18?'낮':hour>=18&&hour<22?'저녁':'밤';
    return `${['첫째','둘째','셋째','넷째','다섯째'][Math.min(day-1,4)]||`${day}번째`} 날 · ${band}`;
  };
  const save=()=>{ try {sessionStorage.setItem(STORAGE,JSON.stringify(game));}catch{} };
  function load(){try{const v=JSON.parse(sessionStorage.getItem(STORAGE));if(v&&v.version===1&&LOCATIONS[v.location]&&Array.isArray(v.history))return v;}catch{} return newGame();}
  function add(kind,text,speaker='',persist=true){const entry={kind,text,speaker,at:game.seconds,id:`${game.turn}-${game.history.length}`};if(persist){game.history.push(entry);save();}display(entry);return entry;}
  function display(item){const feed=$('feed');const wasNearBottom=feed.scrollHeight-feed.scrollTop-feed.clientHeight<92;const p=document.createElement('p');p.className=item.kind;
    if(item.kind==='speech'){const name=document.createElement('span');name.className='speaker';name.textContent=item.speaker;p.append(name);p.append(document.createTextNode('“'+item.text+'”'));}
    else if(item.kind==='player') p.textContent='› '+item.text;
    else p.textContent=item.text;
    feed.append(p);if(item.kind==='speech'||item.kind==='narration')p.style.fontSize=(16*settings.font/100)+'px';
    if(wasNearBottom||item.kind==='player'){requestAnimationFrame(()=>{feed.scrollTop=feed.scrollHeight;});$('jumpLatest').hidden=true;}else $('jumpLatest').hidden=false;
  }
  const chronicle=(title,description)=>{if(!game.chronicle.some(x=>x.title===title)){game.chronicle.push({title,description,at:game.seconds});add('sysnote','새로운 기록이 연대기에 추가되었다.');}};
  const passage=(text)=>add('narration',text);
  function opening(){if(game.history.length)return;passage('늦은 비가 골목의 돌바닥을 두드린다. 당신이 여관 문을 밀어 열자, 젖은 망토 끝에서 빗물이 흘러내린다.');passage('벽난로 가까이에서 여관 주인이 낡은 장부를 덮는다. 그의 시선은 당신이 들고 있는 빈 여행 수첩에 잠시 머문다.');add('speech','이 시간에 북쪽 길로 가려는 건 아니겠지. 밤숲에는 이름을 기억하지 못하는 것들이 있거든.','여관 주인');}
  function updateHeader(){const loc=LOCATIONS[game.location];$('placeLabel').textContent=loc.label;$('timeLabel').textContent=getTime(game.seconds);$('sceneTitle').textContent=loc.chapter;$('scene').setAttribute('aria-label',loc.alt);$('sceneStatus').textContent=game.location==='inn'?'이야기가 시작되는 곳':game.location==='road'?'숲은 조용히 움직인다':'기록은 잠들지 않는다';}
  function suggestions(){const list=game.location==='inn'?["여관 주인에게 북쪽 길에 대해 묻는다","여관 내부를 살펴본다","북쪽 숲길로 나선다"]:game.location==='road'?["숲길을 자세히 살펴본다","폐허가 된 서고로 향한다","여관으로 돌아간다"]:["빛나는 책장을 조사한다","낡은 기록을 읽는다","숲길로 되돌아간다"];
    $('suggestions').replaceChildren();for(const str of list){const b=document.createElement('button');b.className='suggestion';b.type='button';b.textContent=str;b.onclick=()=>{const t=$('actionInput');t.value=str;t.focus();resize(t);tell('행동을 입력창에 채웠습니다. Enter로 확정하세요.');};$('suggestions').append(b);}
  }
  function notifyInvalid(){add('sysnote','그 행동은 아직 세계의 규칙으로 판정할 수 없다. 주변을 살피거나, 인물과 이야기하거나, 다른 장소로 이동해 보자.');}
  function advance(seconds){const before=game.seconds;game.seconds+=seconds;const crossed=before<300&&game.seconds>=300;if(crossed&&!game.flags.bell){game.flags.bell=true;add('sysnote','어디선가 종소리가 한 번 길게 울렸다.');chronicle('밤의 종소리','첫 번째 종소리가 울렸을 때, 세계가 잠시 정적에 잠겼다.');}return crossed;}
  function changeScene(next){game.location=next;updateHeader();suggestions();requestScene(next);
    if(next==='road'){passage('여관을 떠나자 빗줄기는 잦아들었지만 길에는 엷은 안개가 내려앉아 있다. 나무 사이에 오래된 돌 표식 하나가 서 있다.');if(!game.flags.forestSeen){game.flags.forestSeen=true;chronicle('숲의 경계를 넘다','북쪽 숲길에서 안개와 오래된 돌 표식을 발견했다.');} }
    if(next==='archive'){passage('나무의 그늘을 빠져나오자, 무너진 아치와 창문 없는 돌벽이 모습을 드러낸다. 서고 안쪽에서는 설명할 수 없는 푸른 빛이 새어 나온다.');chronicle('이름 없는 서고를 발견하다','숲 깊은 곳에서 폐허가 된 기록의 장소를 찾았다.');}
    if(next==='inn'){passage('여관의 벽난로는 여전히 타오르고 있다. 주인은 당신을 힐끗 보지만, 돌아온 이유를 묻지 않는다.');}
  }
  // Offline dialogue fallback is a scripted UX bridge, NOT a language model.
  // When a secured narration API is configured, client-side conversation will be
  // routed to that service; no AI provider key belongs in this public bundle.
  function freeformDialogue(text){
    const t=text.toLowerCase().replace(/\s+/g,' ').trim();
    const atInn=game.location==='inn';
    const asksRoom=/(잘\s*곳|묵을|숙박|하룻밤|방이|방을|객실|잠자리|자고\s*갈|쉴\s*곳|자는\s*곳)/.test(t);
    const asksPrice=/(얼마|가격|값이|비용|동전|숙박비|요금)/.test(t);
    const isGreeting=/(안녕|반갑|좋은\s*밤|실례|처음\s*뵙)/.test(t);
    const isQuestion=/(\?|요[.?]?|까요[.?]?|습니까[.?]?|는지요[.?]?|어디|누구|무엇|왜|어떻게|알려|설명|궁금|있나|있소)/.test(t);
    if(atInn){
      // Distinguish booking enquiry from actually resting: inquiry never advances hours.
      if(asksRoom || asksPrice){
        advance(60);game.flags.metKeeper=true;
        passage('주인은 장부를 펼쳐 손가락으로 빈 칸을 더듬는다. 벽난로의 불빛이 그의 손등을 스친다.');
        add('speech',asksPrice
          ? '방값은 오늘 밤 장부를 확인해야겠소. 머물 생각이라면 먼저 이름을 적어 두시오. 결제나 방 배정은 아직 이루어지지 않았소.'
          : '밤을 보내실 곳을 찾는군. 우선 불가에서 몸을 녹이시오. 빈방이 있는지 장부를 살펴보겠소.','여관 주인');
        return true;
      }
      if(isGreeting){
        advance(35);game.flags.metKeeper=true;
        add('speech','어서 오시오, 여행자. 비를 많이 맞았군. 이 밤에 무엇을 찾으시오?','여관 주인');
        return true;
      }
      if(isQuestion || /(주인|그에게|당신|그대|부탁|청하)/.test(t)){
        advance(45);game.flags.metKeeper=true;
        add('speech','그 물음에 쉽게 대답하긴 어렵겠군. 내가 아는 것이라면 말해 주겠소. 무엇이 가장 궁금하오?','여관 주인');
        return true;
      }
      passage('여관 주인은 당신의 말을 듣고 잠시 생각한다. 그는 성급히 약속하지 않고 낡은 장부 쪽으로 시선을 돌린다.');
      add('speech','무슨 뜻인지는 알겠소. 구체적으로 무엇을 원하시오?','여관 주인');
      advance(30);return true;
    }
    if(game.location==='road'){
      advance(20);
      passage('당신의 말이 젖은 나무들 사이에 머문다. 숲은 대답하지 않지만, 선택하지 않은 길도 여전히 그 자리에 있다.');
      return true;
    }
    advance(20);
    passage('서고는 당신의 물음에 답하는 대신 먼지 낀 책장과 흐릿한 인장을 보여 준다. 지금은 이곳에서 확인할 수 있는 사실만 알 수 있다.');
    return true;
  }
  function resolve(text){const input=text.toLowerCase().replace(/\s+/g,' ').trim();const has=(...terms)=>terms.some(x=>input.includes(x));
    const current=game.location;
    if(has('돌아','되돌','귀환','back','여관으로')&&current!=='inn'){
      const next=current==='archive'?'road':'inn';advance(120);changeScene(next);return true;}
    if(has('서고','기록관','archive','폐허로')&&current==='road'&&has('가','향','이동','들어','찾')){advance(120);changeScene('archive');return true;}
    if(has('북쪽','숲길','forest','나선','떠난','나가')&&current==='inn'&&has('가','향','이동','나선','나가','떠난')){advance(120);changeScene('road');return true;}
    if(has('숲길','밖으로','돌아')&&current==='archive'&&has('가','향','이동','돌아','나가')){advance(120);changeScene('road');return true;}
    if(!/(잘\s*곳|숙박|묵을|잠자리|자고\s*갈|쉴\s*곳|얼마|가격)/.test(input) && has('쉬자','쉰다','휴식한다','잠을 잔다','잠을 자','기다린다','시간을 보낸다','rest','대기한다','밤을 보낸다')){
      const interrupted=advance(3600);passage(interrupted?'잠시 눈을 감자 멀리서 들려온 종소리가 당신을 깨운다. 밤은 아직 깊다.':'모닥불의 숨소리와 바깥의 바람 소리 속에서 시간이 흘러간다. 세상은 당신을 기다려 주지 않는다.');return true;}
    if(current==='inn'){
      if(has('대화','물어','묻','질문','말','이야기','여관 주인','주인에게','keeper','소문')){
        advance(60);game.flags.metKeeper=true;
        if(has('북쪽','숲','길','소문','수첩','이름')){game.flags.heardNorth=true;add('speech','숲 안쪽에 오래된 서고가 있소. 문은 오래전에 닫혔지만, 누군가는 아직 그 안에서 책장을 넘긴다더군. 돌 표식을 따라가시오.','여관 주인');chronicle('서고에 관한 소문','여관 주인에게 북쪽 숲 깊은 곳에 있다는 오래된 서고 이야기를 들었다.');}
        else add('speech','길을 나서기 전에 몸을 녹이는 게 좋을 거요. 북쪽을 묻는 이들이 요즘 부쩍 늘었으니.','여관 주인');return true;
      }
      if(has('살펴','조사','둘러','관찰','찾','inspect','벽난로','장부','종')){advance(20);passage('낡은 들보에는 매듭처럼 새겨진 문양이 있고, 벽난로 선반에는 주인의 낡은 잉크병이 놓여 있다. 장부 한 귀퉁이가 유난히 자주 접혀 있다.');return true;}
    }
    if(current==='road'){
      if(has('살펴','조사','둘러','관찰','표식','읽','inspect','찾','탐색','소리','흔적')){advance(20);passage('돌 표식에는 글자 대신 나선과 별 하나가 새겨져 있다. 희미한 발자국은 동쪽으로 꺾여 오래된 석조 건물 쪽으로 이어진다.');return true;}
      if(has('말','외쳐','대화','소리','부르')){advance(60);passage('당신의 목소리는 나무줄기 사이로 흩어진다. 아무도 대답하지 않지만, 한 박자 늦게 나뭇잎이 흔들린다.');return true;}
    }
    if(current==='archive'){
      if(has('살펴','조사','읽','책','서고','기록','문양','빛','흔적','inspect','찾','만져')){
        advance(20);
        if(!game.flags.foundFragment){game.flags.foundFragment=true;game.inventory.push({name:'잊힌 기록의 파편',desc:'지워진 이름 아래 희미하게 남은 별의 인장.',icon:'✧'});passage('책장의 깊숙한 곳에서 한 장의 종이가 미끄러져 나온다. 지워진 이름 아래, 별 하나를 감싼 인장이 보인다. 당신이 그것을 손에 넣자 푸른 빛이 아주 잠깐 사라진다.');chronicle('잊힌 기록의 파편','폐허가 된 서고에서 별의 인장이 찍힌 기록 한 장을 발견했다.');}
        else passage('방은 여전히 조용하다. 이미 발견한 기록의 자리에 먼지만 희미하게 쌓여 있다. 문틈 너머의 어둠은 아직 탐험을 기다린다.');return true;
      }
      if(has('말','외쳐','부르','대화')){advance(60);passage('침묵 속에서 당신의 말이 천장에 부딪혀 돌아온다. 서고는 대답 대신 잊힌 종이 냄새를 내보낸다.');return true;}
    }
    if(has('지도','인벤토리','소지품','상태','기록','일지','연대기')){openWindow(has('지도')?'map':has('인벤토리','소지품')?'inventory':has('상태')?'character':'chronicle');add('sysnote','정보창을 열었다. 이것은 세계 행동이 아니므로 시간이 흐르지 않는다.');return true;}
    return false;
  }
  async function submit(){if(busy)return;const textarea=$('actionInput');const text=textarea.value.trim();if(!text)return;if(text.length>360){tell('입력이 너무 깁니다.');return;}busy=true;$('sendAction').disabled=true;$('statusText').textContent='세계가 당신의 행동을 판단하고 있습니다…';game.turn++;game.draft='';add('player',text);rememberedDraft='';historyIndex=-1;textarea.value='';resize(textarea);
    // Offline demo: natural dialogue fallback is scripted, not generative AI.
    await new Promise(resolve=>setTimeout(resolve,120));
    const supported=resolve(text);if(!supported) freeformDialogue(text);save();updateHeader();suggestions();refreshWindows();busy=false;$('sendAction').disabled=false;$('statusText').textContent='Enter 행동 확정 · Shift+Enter 줄바꿈';textarea.focus();}
  function resize(t){t.style.height='auto';t.style.height=Math.min(t.scrollHeight,116)+'px';}
  function tell(s){const t=$('toast');t.textContent=s;t.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>t.hidden=true,2400);}
  function windowContents(id){const t=game.seconds;const current=LOCATIONS[game.location];switch(id){
    case'character':return `<h3>여행자</h3><div class="data-row"><span>이름</span><span>이름 없는 여행자</span></div><div class="data-row"><span>현재 위치</span><span>${esc(current.label)}</span></div><div class="data-row"><span>여정의 시간</span><span>${esc(getTime(t))}</span></div><div class="data-row"><span>방문 지역</span><span>${(game.flags.forestSeen?2:1)+(game.chronicle.some(x=>x.title==='이름 없는 서고를 발견하다')?1:0)}곳</span></div><p class="window-note">데모 모드에서는 수치형 능력치와 장비 효과를 임의로 만들어 내지 않습니다.</p>`;
    case'inventory':return `<h3>소지품 · ${game.inventory.length}개</h3>`+game.inventory.map(x=>`<div class="item-row"><span class="item-icon" aria-hidden="true">${esc(x.icon)}</span><span class="item-detail"><strong>${esc(x.name)}</strong><small>${esc(x.desc)}</small></span></div>`).join('')+'<p class="window-note">물품 사용은 아직 구현되지 않았습니다. 새로운 발견은 여정 중 추가됩니다.</p>';
    case'chronicle':return '<h3>여정이 남긴 기록</h3>'+game.chronicle.map(x=>`<div class="chronicle-event"><strong>${esc(x.title)}</strong><span>${esc(x.description)}</span></div>`).join('')+'<p class="window-note">목격하거나 발견한 사실만 표시합니다. 미발견 세계관은 기록하지 않습니다.</p>';
    case'map':return `<h3>발견한 장소</h3><div class="mini-map"><div class="map-node ${game.location==='inn'?'is-active':''}">잿빛 여관</div><div class="map-link">───</div><div class="map-node ${game.location==='road'?'is-active':''}">${game.flags.forestSeen?'북쪽 숲길':'미발견'}</div>${game.flags.forestSeen?`<div></div><div class="map-link" style="transform:rotate(90deg)">───</div><div></div><div></div><div></div><div class="map-node ${game.location==='archive'?'is-active':''}">${game.chronicle.some(x=>x.title==='이름 없는 서고를 발견하다')?'오래된 서고':'미발견'}</div>`:''}</div><p class="window-note">지도는 발견한 지역만 보여 줍니다. 이동은 메인에서 행동으로 입력하세요.</p>`;
    case'log':return '<h3>여정 기록 · 최근부터</h3><div class="log-entries">'+game.history.slice().reverse().map(x=>`<div class="item-row"><div><small style="color:#97866d">${x.kind==='speech'?esc(x.speaker):x.kind==='player'?'나의 행동':x.kind==='sysnote'?'기록':'서술'}</small><div>${esc(x.text)}</div></div></div>`).join('')+'</div>';
    case'settings':return `<h3>표시 설정</h3><label class="switch-row"><span>도트 장면 표시</span><input id="optArt" type="checkbox" ${settings.art?'checked':''}></label><label class="switch-row"><span>움직임 최소화</span><input id="optMotion" type="checkbox" ${settings.reduced?'checked':''}></label><div class="switch-row"><label for="optFont">본문 글자 크기</label><select id="optFont"><option value="90" ${settings.font===90?'selected':''}>작게</option><option value="100" ${settings.font===100?'selected':''}>보통</option><option value="115" ${settings.font===115?'selected':''}>크게</option><option value="130" ${settings.font===130?'selected':''}>더 크게</option></select></div><h3>데모 데이터</h3><button id="resetGame" class="suggestion" type="button">새로운 여정 시작</button><p class="window-note">진행 상황은 현재 브라우저 탭의 세션에만 저장됩니다. 실제 World Engine의 데이터가 아닙니다.</p>`;
    case'help':return `<h3>첫 번째 밤을 위한 안내</h3><p>원하는 행동을 문장으로 입력한 다음 Enter를 누르세요. 추천 행동은 입력창을 채울 뿐 바로 실행하지 않습니다.</p><div class="data-row"><span>Enter / Shift+Enter</span><span>행동 확정 / 줄바꿈</span></div>${WIN.slice(0,5).map(x=>`<div class="data-row"><span>${esc(x.label)}</span><span>${esc(x.key)}</span></div>`).join('')}<div class="data-row"><span>설정 / 최상단 창 닫기</span><span>Esc</span></div><div class="data-row"><span>입력창으로 이동</span><span>F2</span></div><p class="window-note">텍스트 편집 중에는 문자 단축키가 작동하지 않습니다. 한글 입력 도중 Enter로 행동이 전송되지 않도록 조합 상태도 확인합니다.</p>`;
  }}
  function refreshWindows(){for(const w of document.querySelectorAll('.float-window')){const target=w.querySelector('.window-body');if(w.dataset.id!=='settings'&&target)target.innerHTML=windowContents(w.dataset.id);}}
  let z=30;
  function openWindow(id){const sys=id==='help'||id==='settings';$('menuPopover').hidden=true;if(sys){if(modal===id){closeWindow(id);return;}if(modal)closeWindow(modal);modal=id;}else {if(openWindows.includes(id)){closeWindow(id);return;}if(window.innerWidth<700){openWindows.slice().forEach(closeWindow);}else if(openWindows.length>=2)closeWindow(openWindows[0]);openWindows.push(id);}
    const el=document.createElement('section');el.className='float-window'+(sys?' modal-window':'');el.dataset.id=id;el.setAttribute('role',sys?'dialog':'region');if(sys)el.setAttribute('aria-modal','true');const spec=WIN.find(x=>x.id===id);el.setAttribute('aria-label',spec.label);el.style.zIndex=String(++z);if(!sys&&openWindows.length===2)el.dataset.shift='1';el.innerHTML=`<div class="window-head"><span class="window-title">${esc(spec.label)}</span><span class="window-actions"><span class="window-key">${esc(spec.key)}</span><button class="close-window" aria-label="닫기" type="button">×</button></span></div><div class="window-body">${windowContents(id)}</div>`;el.querySelector('.close-window').onclick=()=>closeWindow(id);$('windowLayer').append(el);el.querySelector('.close-window').focus();if(id==='settings')attachSettings(el);}
  function closeWindow(id){const node=Array.from(document.querySelectorAll('.float-window')).find(x=>x.dataset.id===id);if(node)node.remove();openWindows=openWindows.filter(x=>x!==id);if(modal===id)modal=null;const top=document.querySelector('.float-window:last-child');if(top)top.querySelector('.close-window').focus();else $('openMenu').focus();}
  function attachSettings(el){el.querySelector('#optArt').onchange=e=>{settings.art=e.target.checked;applySettings();};el.querySelector('#optMotion').onchange=e=>{settings.reduced=e.target.checked;applySettings();};el.querySelector('#optFont').onchange=e=>{settings.font=Number(e.target.value);applySettings();};el.querySelector('#resetGame').onclick=()=>{if(!confirm('현재 탭에 저장된 데모 여정을 초기화할까요?'))return;game=newGame();save();$('feed').replaceChildren();openWindows.slice().forEach(closeWindow);if(modal)closeWindow(modal);opening();updateHeader();suggestions();requestScene('inn');tell('새 여정이 시작되었습니다.');};}
  function applySettings(){document.body.classList.toggle('no-art',!settings.art);document.body.classList.toggle('reduced',settings.reduced);document.documentElement.style.setProperty('--reading-scale',settings.font/100);document.querySelector('.feed').style.fontSize=settings.font+'%';document.querySelectorAll('.feed p:not(.sysnote):not(.player)').forEach(p=>p.style.fontSize=(16*settings.font/100)+'px');try{localStorage.setItem(SETTINGS,JSON.stringify(settings));}catch{}}
  function syncFont(){document.querySelectorAll('.feed p:not(.sysnote):not(.player)').forEach(p=>p.style.fontSize=(16*settings.font/100)+'px');}
  // A tiny, fully offline pixel renderer: scenery is presentation, not canonical world data.
  function renderScene(location){const canvas=$('scene'),ctx=canvas.getContext('2d');const W=384,H=216;ctx.imageSmoothingEnabled=false;const fill=(x,y,w,h,c)=>{ctx.fillStyle=c;ctx.fillRect(Math.round(x),Math.round(y),Math.round(w),Math.round(h));};
    let seed=({inn:443,road:182,archive:907})[location];const rnd=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296;};
    const dots=(count,x0,y0,x1,y1,colors,w=1,h=1)=>{for(let i=0;i<count;i++){const x=x0+rnd()*(x1-x0),y=y0+rnd()*(y1-y0);fill(x,y,w,h,colors[Math.floor(rnd()*colors.length)]);}};
    ctx.clearRect(0,0,W,H);
    if(location==='inn'){
      fill(0,0,W,H,'#171923');fill(0,0,W,100,'#27252a');dots(750,0,0,W,110,['#29262c','#22232b','#343035','#292935'],4,3);fill(0,106,W,110,'#27211e');
      for(let i=0;i<20;i++)fill(0,106+i*6,W,1,i%2?'#342921':'#201b1b');
      for(let i=0;i<18;i++){const x=i*25+10;fill(x,103,1,110,'#3a2c24');}fill(0,17,W,10,'#17171b');fill(0,18,W,3,'#4c3828');fill(0,94,W,7,'#16171c');
      fill(270,22,68,65,'#161c27');fill(273,25,62,59,'#333645');fill(275,27,58,55,'#18293a');fill(300,26,5,58,'#29202c');fill(274,52,60,5,'#2b202c');dots(120,278,30,331,82,['#273b4b','#1b2c3f','#354654'],2,2);
      fill(14,44,104,95,'#15151a');fill(20,49,92,82,'#484039');fill(25,55,82,70,'#222124');fill(30,80,68,39,'#3a2722');fill(42,81,42,31,'#e2a15c');for(let i=0;i<90;i++){const x=38+rnd()*48,y=73+rnd()*45;fill(x,y,2+rnd()*7,3+rnd()*6,['#a45a36','#e9a555','#e8c17b','#693b2d'][Math.floor(rnd()*4)]);}fill(12,137,118,5,'#554135');
      fill(155,52,11,92,'#49382d');fill(155,46,17,8,'#584632');fill(148,138,40,9,'#302522');fill(188,46,11,97,'#342824');fill(204,50,49,6,'#594432');fill(207,58,6,46,'#47382e');fill(249,59,6,50,'#47382e');fill(207,104,48,4,'#59432b');
      fill(205,112,107,8,'#58402b');fill(217,119,8,46,'#3a2b28');fill(298,119,8,46,'#3a2b28');fill(204,163,111,7,'#231e20');fill(227,90,17,23,'#a4773b');fill(230,76,11,16,'#d49d50');fill(232,68,8,10,'#ecba6a');fill(231,60,6,9,'#f6d695');fill(225,96,25,15,'#443327');
      fill(320,112,26,48,'#25222a');fill(319,100,30,20,'#453b35');fill(319,99,30,7,'#857055');fill(321,96,26,5,'#9a7854');fill(321,160,37,5,'#1b1b1d');fill(18,170,345,12,'#4a352c');fill(23,182,12,34,'#31231f');fill(353,182,10,34,'#322421');dots(420,0,150,W,216,['#302724','#3a2d27','#242224','#4a3428'],3,2);
      for(let i=0;i<12;i++){const x=150+rnd()*165,y=100+rnd()*54;fill(x,y,2,2,['#9f7441','#c79a5f','#5d513a'][Math.floor(rnd()*3)]);}
    }else if(location==='road'){
      fill(0,0,W,H,'#151c29');for(let y=0;y<130;y+=4)fill(0,y,W,4,y<60?'#20283a':'#222b33');fill(280,23,26,26,'#adb2ac');fill(286,18,23,28,'#d0cbb7');dots(240,0,0,W,100,['#65717a','#424c60','#a5a7a0'],1,1);
      for(let i=0;i<45;i++){const x=rnd()*W,y=45+rnd()*105,w=8+rnd()*31;fill(x,y,w,95,'#132127');fill(x+w/3,y-12,w/2,26,'#1c3031');}fill(0,137,W,79,'#202d2c');for(let i=0;i<30;i++){const x=rnd()*W,y=65+rnd()*125;fill(x,y,4+rnd()*10,110,'#161f24');fill(x-13,y-8,32,6,'#17262c');fill(x-5,y-15,25,7,'#1c2d30');}fill(138,157,90,59,'#45403a');fill(145,161,77,55,'#67584c');fill(177,158,32,58,'#75645a');dots(270,0,155,W,H,['#303b33','#384234','#22302b','#49463b'],3,2);fill(198,117,19,57,'#474846');fill(196,110,23,13,'#6c6b5e');fill(199,108,6,5,'#b0a17c');fill(207,108,6,5,'#b0a17c');fill(200,126,11,3,'#2d2e2c');fill(0,189,W,28,'#171f23');
    }else{
      fill(0,0,W,H,'#151e28');fill(0,0,W,98,'#202833');dots(750,0,0,W,140,['#2c343a','#202a34','#333541','#2b2732'],3,3);fill(0,19,W,7,'#313039');fill(0,60,W,10,'#191d27');fill(0,107,W,109,'#25252c');for(let y=111;y<216;y+=13)fill(0,y,W,2,'#38343a');for(let x=0;x<W;x+=31)fill(x,111,2,105,'#36323a');
      fill(24,37,28,122,'#1b1c25');fill(30,45,24,118,'#524c50');fill(31,51,17,105,'#292831');fill(86,51,31,105,'#191f27');fill(91,55,23,95,'#62594e');fill(96,62,12,84,'#292b32');fill(135,29,37,132,'#20222b');fill(141,34,28,121,'#5b5151');fill(146,43,19,108,'#302c33');fill(253,23,38,136,'#1e222e');fill(259,32,25,122,'#4d4d53');fill(264,37,15,108,'#252c35');fill(320,43,33,111,'#1c242e');fill(327,49,20,102,'#625d58');
      fill(177,52,70,109,'#18242f');fill(182,59,60,99,'#243c48');fill(189,74,47,84,'#345363');dots(130,193,75,232,156,['#688b8c','#718d84','#92b6a4','#4b6d78'],2,3);fill(173,151,81,9,'#66605d');fill(158,157,116,9,'#342e33');
      for(let i=0;i<12;i++){const x=i*28+9;fill(x,153,3,47,'#62544d');fill(x+4,153,12,4,'#67584d');fill(x+4,170,12,4,'#67584d');}fill(0,195,W,21,'#1b1b22');dots(190,0,185,W,H,['#27252b','#373136','#4f4440'],3,2);
      fill(209,131,20,27,'#9b9c85');fill(211,128,19,23,'#c5c3a4');fill(213,132,14,17,'#b8b1a3');fill(219,134,3,14,'#49514e');
    }
    // Ordered Bayer-style dimming for a restrained retro texture.
    ctx.fillStyle='rgba(7,6,9,.14)';for(let y=0;y<H;y+=2)for(let x=(y%4===0?0:1);x<W;x+=2)ctx.fillRect(x,y,1,1);
  }
  function requestScene(location){const generation=++sceneGeneration;artScene=location;requestAnimationFrame(()=>{if(generation!==sceneGeneration)return;renderScene(location);});}
  function keyHandler(e){if(e.key==='Escape'){if(e.isComposing||activeComposition||e.keyCode===229)return;e.preventDefault();if(!$('menuPopover').hidden){$('menuPopover').hidden=true;return;}if(modal){closeWindow(modal);return;}if(openWindows.length){closeWindow(openWindows[openWindows.length-1]);return;}openWindow('settings');return;}
    if(e.key==='F2'){e.preventDefault();$('actionInput').focus();return;}
    if(e.ctrlKey||e.metaKey||e.altKey||e.isComposing||activeComposition||e.keyCode===229)return;
    const target=e.target,isEditor=target&&(['INPUT','TEXTAREA','SELECT'].includes(target.tagName)||target.isContentEditable);if(isEditor)return;
    const id=({c:'character',i:'inventory',k:'chronicle',m:'map',l:'log','?':'help'})[e.key.toLowerCase()];if(id){e.preventDefault();openWindow(id);}
  }
  function init(){try{const pref=JSON.parse(localStorage.getItem(SETTINGS));if(pref&&typeof pref==='object')settings={...settings,...pref};}catch{}game=load();const feed=$('feed');feed.replaceChildren();if(!game.history.length)opening();else for(const item of game.history)display(item);
    updateHeader();suggestions();requestScene(game.location);applySettings();syncFont();const input=$('actionInput');input.value=game.draft||'';resize(input);input.addEventListener('compositionstart',()=>activeComposition=true);input.addEventListener('compositionend',()=>{activeComposition=false;});input.addEventListener('input',()=>{resize(input);game.draft=input.value;save();});input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing&&!activeComposition&&e.keyCode!==229){e.preventDefault();submit();}});$('sendAction').onclick=submit;$('jumpLatest').onclick=()=>{feed.scrollTop=feed.scrollHeight;$('jumpLatest').hidden=true;};feed.addEventListener('scroll',()=>{if(feed.scrollHeight-feed.scrollTop-feed.clientHeight<65)$('jumpLatest').hidden=true;});document.addEventListener('keydown',keyHandler);$('openMenu').onclick=e=>{e.stopPropagation();$('menuPopover').hidden=!$('menuPopover').hidden;};$('menuPopover').onclick=e=>e.stopPropagation();document.addEventListener('click',()=>{$('menuPopover').hidden=true;});
    const menu=$('menuItems');for(const w of WIN){const btn=document.createElement('button');btn.type='button';btn.className='menu-choice';btn.innerHTML=`<span>${esc(w.label)}</span><kbd>${esc(w.key)}</kbd>`;btn.onclick=()=>openWindow(w.id);menu.append(btn);}window.addEventListener('resize',()=>requestScene(game.location));}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init);else init();
})();