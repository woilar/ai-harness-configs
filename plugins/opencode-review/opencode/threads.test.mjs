import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createThreadStore} from './threads.mjs';
const sessionID='ses_test_session_12345',other='ses_other_session_12345';
function fixture(){const data=new Map();const storage={get:async k=>structuredClone(data.get(k)),set:async(k,v)=>{data.set(k,structuredClone(v));}};const store=createThreadStore(storage);const turn={threadID:randomUUID(),id:randomUUID(),text:'Почему null?'};return{store,storage,turn,read:()=>store.read({threads:[{id:turn.threadID,sessionID}]})};}
const marker=t=>`[OpenCodeReview thread=${t.threadID} turn=${t.id}]`;
test('draft registration is idempotent and cannot answer before manual submission',async()=>{const f=fixture();await f.store.prepare({sessionID,turns:[f.turn]});await f.store.prepare({sessionID,turns:[f.turn]});assert.equal((await f.read()).threads[0].turns.length,1);await assert.rejects(f.store.reply({threadID:f.turn.threadID,turnID:f.turn.id,text:'answer'},sessionID,'call'));assert.equal((await f.read()).threads[0].turns[0].admitted,false);});
test('manual prompt admission + addressed reply, persistent across restart',async()=>{const f=fixture();await f.store.prepare({sessionID,turns:[f.turn]});await f.store.admit(sessionID,marker(f.turn));await f.store.reply({threadID:f.turn.threadID,turnID:f.turn.id,text:'**Ответ**\n```java\nnull\n```'},sessionID,'call1');const restarted=createThreadStore(f.storage);const item=(await restarted.read({threads:[{id:f.turn.threadID,sessionID}]})).threads[0].turns[0];assert.equal(item.admitted,true);assert.equal(item.answerID,'call1');assert.match(item.answer,/Ответ/);});
test('other sessions cannot admit, answer, read or rebind a thread',async()=>{const f=fixture();await f.store.prepare({sessionID,turns:[f.turn]});await f.store.admit(other,marker(f.turn));assert.equal((await f.read()).threads[0].turns[0].admitted,false);await assert.rejects(f.store.prepare({sessionID:other,turns:[f.turn]}));await f.store.admit(sessionID,marker(f.turn));await assert.rejects(f.store.reply({threadID:f.turn.threadID,turnID:f.turn.id,text:'wrong'},other,'call'));assert.deepEqual(await f.store.read({threads:[{id:f.turn.threadID,sessionID:other}]}),{threads:[]});});
test('reply retries do not duplicate or silently replace answers',async()=>{const f=fixture();await f.store.prepare({sessionID,turns:[f.turn]});await f.store.admit(sessionID,marker(f.turn));const input={threadID:f.turn.threadID,turnID:f.turn.id,text:'Ответ'};await f.store.reply(input,sessionID,'call1');await f.store.reply(input,sessionID,'call2');await assert.rejects(f.store.reply({...input,text:'different'},sessionID,'call3'));assert.equal((await f.read()).threads[0].turns[0].answerID,'call1');});
test('parallel threads and follow-ups retain their own answers',async()=>{const f=fixture();const b={threadID:randomUUID(),id:randomUUID(),text:'second'};await f.store.prepare({sessionID,turns:[f.turn,b]});await f.store.admit(sessionID,marker(f.turn)+'\n'+marker(b));await Promise.all([f.turn,b].map(t=>f.store.reply({threadID:t.threadID,turnID:t.id,text:t.text+' reply'},sessionID,t.id)));const follow={...f.turn,id:randomUUID(),text:'Уточнение'};await f.store.prepare({sessionID,turns:[follow]});await f.store.admit(sessionID,marker(follow));await f.store.reply({threadID:follow.threadID,turnID:follow.id,text:'Уточняю'},sessionID,'follow');const turns=(await f.read()).threads[0].turns;assert.equal(turns.length,2);assert.equal(turns[0].answer,'Почему null? reply');assert.equal(turns[1].answer,'Уточняю');});
test('malformed refs, oversized payloads and ID reuse fail closed',async()=>{const f=fixture();await assert.rejects(f.store.prepare({sessionID,turns:[{...f.turn,text:'x'.repeat(20001)}]}));await f.store.prepare({sessionID,turns:[f.turn]});await assert.rejects(f.store.prepare({sessionID,turns:[{...f.turn,text:'changed'}]}));await assert.rejects(f.store.reply({threadID:'../bad',turnID:f.turn.id,text:'bad'},sessionID,'call'));await assert.rejects(f.store.read({threads:[{id:'bad',sessionID}]}));});
test('conflicting turn IDs in one batch are rejected before any write',async()=>{const f=fixture();await assert.rejects(f.store.prepare({sessionID,turns:[f.turn,{...f.turn,text:'different'}]}));assert.deepEqual(await f.read(),{threads:[]});});

test('one thread can continue in current session while late old reply stays addressed',async()=>{
 const f=fixture();await f.store.prepare({sessionID,turns:[f.turn]});await f.store.admit(sessionID,marker(f.turn));
 const next={...f.turn,id:randomUUID(),text:'Продолжи здесь'};await f.store.prepare({sessionID:other,turns:[next]});
 await f.store.admit(other,marker(next));await f.store.reply({threadID:next.threadID,turnID:next.id,text:'Ответ нового чата'},other,'new');
 await f.store.reply({threadID:f.turn.threadID,turnID:f.turn.id,text:'Поздний ответ'},sessionID,'old');
 assert.equal((await f.read()).threads[0].turns[0].answer,'Поздний ответ');
 const second=(await f.store.read({threads:[{id:f.turn.threadID,sessionID:other}]})).threads[0];assert.equal(second.turns.length,1);assert.equal(second.turns[0].answer,'Ответ нового чата');
 await assert.rejects(f.store.reply({threadID:next.threadID,turnID:next.id,text:'Wrong'},sessionID,'bad'));
});
test('legacy stored turns retain original ownership when new session continues thread',async()=>{
 const f=fixture();await f.storage.set('thread/'+f.turn.threadID,{id:f.turn.threadID,sessionID,turns:[{id:f.turn.id,text:f.turn.text,admitted:true}]});
 await f.store.prepare({sessionID:other,turns:[{...f.turn,id:randomUUID()}]});await f.store.reply({threadID:f.turn.threadID,turnID:f.turn.id,text:'Legacy reply'},sessionID,'legacy');
 assert.equal((await f.read()).threads[0].turns[0].answer,'Legacy reply');
});

test('fresh delivery identity recovers prepared turn conflict without rebinding history',async()=>{
 const f=fixture();await f.store.prepare({sessionID,turns:[f.turn]});
 await assert.rejects(f.store.prepare({sessionID:other,turns:[{...f.turn,text:'edited'}]}));
 const fresh={...f.turn,id:randomUUID(),text:'edited'};
 await f.store.prepare({sessionID:other,turns:[fresh]});await f.store.admit(other,marker(fresh));
 await f.store.reply({threadID:fresh.threadID,turnID:fresh.id,text:'delivered'},other,'fresh');
 const result=await f.store.read({threads:[{id:fresh.threadID,sessionID:other}]});
 assert.equal(result.threads[0].turns[0].answer,'delivered');
 assert.equal((await f.read()).threads[0].turns[0].admitted,false);
});
