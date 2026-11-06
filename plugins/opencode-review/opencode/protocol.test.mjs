import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createBridge,digest} from './protocol.mjs';
function fixture() {
 let text='Текущий черновик'; let sent=0, sessionID='session-one';
 const field={traits:{owner:'opencode',role:'prompt'},focusable:true,isDestroyed:false,
   get plainText(){return text},gotoBufferEnd(){},insertText(s){text+=s},submit(){sent++}};
 const ctx={app:{version:'2.2.5'},location:{directory:'/project'},data:{session:{get:()=>({title:'Task',location:{directory:'/project'}})},location:{default:()=>({directory:'/project'})}},
   ui:{router:{current:()=>({type:'session',sessionID})}},renderer:{currentFocusedRenderable:field,requestRender(){}}};
 const bridge=createBridge(ctx);
 const request=(id='request-123',value='Новая пачка\nВторая строка')=>({id,text:value,hash:digest(value),instance:bridge.status().instance,sessionID});
 return {bridge,ctx,field,request,get text(){return text},get sent(){return sent},set text(t){text=t},session:s=>sessionID=s};
}
test('append preserves complete draft and never submits; unicode and multiline',()=>{const f=fixture();const before=f.text;const r=f.bridge.append(f.request());assert(r.ok);assert.equal(f.text,before+'\n\n'+f.request().text);assert.equal(f.sent,0);});
test('retry with same id does not duplicate; receipt survives session switch',()=>{const f=fixture();const q=f.request();const r=f.bridge.append(q),text=f.text;f.session('session-two');assert.deepEqual(f.bridge.append(q),r);assert.equal(f.text,text);assert.deepEqual(f.bridge.receipt(q.id),r);});
test('receipt ID collision cannot change payload',()=>{const f=fixture();f.bridge.append(f.request());assert.throws(()=>f.bridge.append(f.request('request-123','Другой текст')));});
test('current session must equal transfer target',()=>{const f=fixture(),q=f.request();f.session('other');assert.throws(()=>f.bridge.append(q));assert.equal(f.text,'Текущий черновик');});
test('refuse dialogs, shell prompt, disabled input and foreign renderables',()=>{for(const change of [f=>f.field.traits.status='SHELL',f=>f.field.traits.owner='other',f=>f.field.traits.role='search',f=>f.field.focusable=false,f=>f.field.isDestroyed=true,f=>f.ctx.renderer.currentFocusedRenderable=null]) {const f=fixture();change(f);assert.equal(f.bridge.status().ready,false);assert.throws(()=>f.bridge.append(f.request()));assert.equal(f.sent,0);}});
test('home is not an implicit recipient',()=>{const f=fixture();f.ctx.ui.router.current=()=>({type:'home'});assert.throws(()=>f.bridge.append(f.request()));});
test('slash and shell command drafts remain untouched',()=>{for(const s of ['/compact','!rm something','  /review']) {const f=fixture();f.text=s;assert.throws(()=>f.bridge.append(f.request()));assert.equal(f.text,s);}});
test('untrusted/malformed inputs cannot mutate draft',()=>{for(const change of [q=>q.hash='wrong',q=>q.instance='other',q=>q.text='\x1b[13~',q=>q.text='',q=>q.id='../../a',q=>q.text='a'.repeat(100001)]) {const f=fixture(),q=f.request();change(q);assert.throws(()=>f.bridge.append(q));assert.equal(f.text,'Текущий черновик');}});
test('empty draft gets no leading separator',()=>{const f=fixture();f.text='';f.bridge.append(f.request());assert.equal(f.text,f.request().text);});
test('failed verification does not create positive receipt',()=>{const f=fixture();f.field.insertText=()=>{};assert.throws(()=>f.bridge.append(f.request()));assert(!f.bridge.receipt('request-123')?.ok);});
test('attachments/traits stay intact and no paste compaction is used',()=>{const f=fixture();f.field.extraAttachment={id:1};f.bridge.append(f.request());assert.deepEqual(f.field.extraAttachment,{id:1});assert.equal(f.field.traits.role,'prompt');});
test('unknown partial insertion is blocked on retry instead of duplicated',()=>{const f=fixture();f.field.insertText=s=>f.text+=s.slice(0,4);const q=f.request();assert.throws(()=>f.bridge.append(q));const after=f.text;assert.throws(()=>f.bridge.append(q));assert.equal(f.text,after);assert.equal(f.bridge.receipt(q.id).ok,false);});

function sendingFixture(){const f=fixture();let submissions=0,admitted=false,prepares=0,seen='';
 f.ctx.keymap={dispatch:id=>{assert.equal(id,'prompt.submit');submissions++;seen=f.text;f.text='';admitted=true;}};
 const bridge=createBridge(f.ctx,{prepare:async()=>{prepares++;},confirm:async()=>admitted});
 const request=()=>({...f.request(),instance:bridge.status().instance,draftHash:digest(f.text)});
 return {f,bridge,request,get submissions(){return submissions},get prepares(){return prepares},get seen(){return seen},set admitted(v){admitted=v}};
}
test('send uses native prompt.submit once and confirms server admission, not only insertion',async()=>{
 const t=sendingFixture(),before=t.f.text,q=t.request();const [a,b]=await Promise.all([t.bridge.send(q),t.bridge.send(q)]);
 assert.equal(a.submitted,true);assert.equal(b.submitted,true);assert.equal(t.submissions,1);assert.equal(t.prepares,1);assert.equal(t.seen,before+'\n\n'+q.text);
 t.f.session('other');assert.deepEqual(await t.bridge.send(q),a);assert.equal(t.submissions,1);
});
test('send rejects changed terminal draft or target before mutation',async()=>{
 for(const change of [t=>t.f.text+='!',t=>t.f.session('other')]){const t=sendingFixture(),q=t.request();change(t);const before=t.f.text;await assert.rejects(t.bridge.send(q));assert.equal(t.f.text,before);assert.equal(t.submissions,0);}
});
test('session change during server registration does not insert or submit',async()=>{
 const t=sendingFixture();const bridge=createBridge(t.f.ctx,{prepare:async()=>t.f.session('other')});const q={...t.request(),instance:bridge.status().instance};await assert.rejects(bridge.send(q));assert.equal(t.submissions,0);assert.equal(t.f.text,'Текущий черновик');
});
test('append receipt cannot authorize send and send collision cannot alter payload',async()=>{
 const t=sendingFixture(),q=t.request();t.bridge.append(q);await assert.rejects(t.bridge.send(q));assert.equal(t.submissions,0);
 const t2=sendingFixture(),q2=t2.request();await t2.bridge.send(q2);await assert.rejects(t2.bridge.send({...q2,text:'other',hash:digest('other')}));assert.equal(t2.submissions,1);
});
test('cleared composer without admitted server turn remains unknown and cannot duplicate',async()=>{
 const t=sendingFixture();t.f.ctx.keymap.dispatch=()=>{t.f.text='';};const q=t.request();const r=await t.bridge.send(q);assert.equal(r.ok,false);assert.equal(r.submitted,false);
 const after=t.f.text;await t.bridge.send(q);assert.equal(t.f.text,after);t.admitted=true;assert.equal((await t.bridge.checkReceipt(q.id)).submitted,true);
});
