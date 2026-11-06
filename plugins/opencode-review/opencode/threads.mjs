const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const session = value => typeof value === 'string' && /^ses_[a-zA-Z0-9_-]{8,100}$/.test(value);
const content = value => typeof value === 'string' && value.trim() && value.length <= 20000;
/** All writes serialize: tool calls, prompt admission and RPC can arrive concurrently. */
export function createThreadStore(storage) {
  let lock=Promise.resolve();
  const exclusive=fn=>{const next=lock.then(fn);lock=next.catch(()=>{});return next;};
  const key=id=>'thread/'+id;
  const owner=(thread,turn)=>turn.sessionID??thread.sessionID;
  function validTurn(t){if(!t||!uuid(t.threadID)||!uuid(t.id)||!content(t.text))throw new Error('Invalid review turn');}
  return {
    prepare: input=>exclusive(async()=>{
      if(!input||!session(input.sessionID)||!Array.isArray(input.turns)||!input.turns.length||input.turns.length>100)throw new Error('Invalid review batch');
      input.turns.forEach(validTurn);
      const seen=new Map();for(const turn of input.turns){const k=turn.threadID+'/'+turn.id;if(seen.has(k)&&seen.get(k)!==turn.text)throw new Error('Duplicate turn payload');seen.set(k,turn.text);}
      // Validate the whole batch before writes; never rebind a thread or overwrite a turn.
      for(const turn of input.turns){const old=await storage.get(key(turn.threadID));
        if(old&&(old.turns.some(t=>t.id===turn.id&&(t.text!==turn.text||owner(old,t)!==input.sessionID))))throw new Error('Review thread belongs to another session or payload');
        if(old&&old.turns.length>=100&&!old.turns.some(t=>t.id===turn.id))throw new Error('Thread history limit reached');}
      for(const turn of input.turns){const thread=await storage.get(key(turn.threadID))??{id:turn.threadID,sessionID:input.sessionID,turns:[]};
        for(const prior of thread.turns)prior.sessionID=owner(thread,prior);
        if(!thread.turns.some(t=>t.id===turn.id))thread.turns.push({id:turn.id,text:turn.text,sessionID:input.sessionID,admitted:false});
        await storage.set(key(turn.threadID),thread);}
      return {ok:true};
    }),
    admit: (sessionID,text)=>exclusive(async()=>{
      if(!session(sessionID)||typeof text!=='string')return;
      for(const m of text.matchAll(/^\[OpenCodeReview thread=([0-9a-f-]{36}) turn=([0-9a-f-]{36})\]$/gm)){
        const thread=await storage.get(key(m[1]));if(!thread)continue;
        const turn=thread.turns.find(t=>t.id===m[2]);if(!turn||owner(thread,turn)!==sessionID||turn.admitted)continue;
        turn.admitted=true;await storage.set(key(thread.id),thread);
      }
    }),
    reply: (input,sessionID,callID)=>exclusive(async()=>{
      if(!input||!uuid(input.threadID)||!uuid(input.turnID)||!content(input.text)||!session(sessionID))throw new Error('Invalid review reply context');
      const thread=await storage.get(key(input.threadID));
      if(!thread)throw new Error('Review thread not found');
      const turn=thread.turns.find(t=>t.id===input.turnID);
      if(!turn||owner(thread,turn)!==sessionID)throw new Error('Review turn belongs to another session');
      if(!turn.admitted)throw new Error('Review turn was not submitted by the user');
      if(turn.answer){if(turn.answer!==input.text)throw new Error('Review turn already answered');return {content:turn.answer};}
      turn.answer=input.text;turn.answerID=String(callID||input.turnID);await storage.set(key(thread.id),thread);
      return {content:input.text};
    }),
    read: input=>exclusive(async()=>{
      if(!input||!Array.isArray(input.threads)||input.threads.length>30)throw new Error('Invalid review query');
      const threads=[];for(const ref of input.threads){if(!uuid(ref.id)||!session(ref.sessionID))throw new Error('Invalid review reference');
        const thread=await storage.get(key(ref.id));if(thread){const turns=thread.turns.filter(t=>owner(thread,t)===ref.sessionID);if(turns.length)threads.push({...thread,sessionID:ref.sessionID,turns});}}
      return {threads};
    }),
  };
}
