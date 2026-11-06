import { Plugin } from '@opencode/plugin';
import { ReviewRpc } from './rpc.js';
import { createThreadStore } from './threads.mjs';
export default Plugin.define({id:'opencode-review-bridge',async setup(ctx){
  const store=createThreadStore(ctx.storage);
  await ctx.rpc.register(ReviewRpc,{
    ping:async()=>({version:'3.0.0'}),
    prepare:async input=>{await ctx.session.get({sessionID:input.sessionID});return store.prepare(input);},
    read:input=>store.read(input),
  });
  await ctx.session.hook('prompt',event=>store.admit(event.sessionID,event.prompt.text));
  await ctx.tool.transform(editor=>editor.add({
    name:'review_reply',options:{codemode:false},
    description:'Publish a reply in an IntelliJ code-review thread. Use the exact threadID and turnID from the [OpenCodeReview ...] marker in the user message. Reply to each requested review turn once. Never mark a thread resolved; the user does that.',
    input:{type:'object',properties:{threadID:{type:'string'},turnID:{type:'string'},text:{type:'string',maxLength:20000}},required:['threadID','turnID','text'],additionalProperties:false},
    execute:(input,context)=>store.reply(input,context.sessionID,context.callID),
  }));
}});
