import { Plugin } from '@opencode/plugin/tui';
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { ReviewRpc } from './rpc.js';
import { createBridge } from './protocol.mjs';

export default Plugin.define({
  id: 'opencode-review-bridge',
  setup(context) {
    const bridge = createBridge(context, {
      prepare:input=>context.client.rpc(ReviewRpc).prepare({sessionID:input.sessionID,turns:input.turns}),
      confirm:async receipt=>{
        if(!receipt.turns?.length)return false;
        const refs=[...new Set(receipt.turns.map(t=>t.threadID))].map(id=>({id,sessionID:receipt.sessionID}));
        const confirmed=new Set();
        for(let i=0;i<refs.length;i+=30){const result=await context.client.rpc(ReviewRpc).read({threads:refs.slice(i,i+30)});
          for(const thread of result.threads)for(const turn of thread.turns)if(turn.admitted)confirmed.add(thread.id+'/'+turn.id);}
        return receipt.turns.every(t=>confirmed.has(t.threadID+'/'+t.id));
      },
    });
    const publicReceipt=r=>{if(!r)return null;const {turns,...safe}=r;return safe;};
    const token = randomBytes(32).toString('hex');
    const directory = process.env.OPENCODE_REVIEW_RUNTIME || join(homedir(), '.local', 'share', 'opencode-review', 'clients');
    mkdirSync(directory, {recursive:true, mode:0o700});
    const server = Bun.serve({
      hostname:'127.0.0.1', port:0, maxRequestBodySize:600000,
      async fetch(request) {
        if (request.headers.has('origin') || request.headers.get('authorization') !== `Bearer ${token}`)
          return Response.json({error:'Unauthorized'}, {status:403});
        const url = new URL(request.url);
        try {
          if (request.method === 'GET' && url.pathname === '/status') {
            let threads=false;try{const version=await context.client.rpc(ReviewRpc).ping({});threads=version.version==='3.0.0';}catch{}
            return Response.json({...bridge.status(),threads});
          }
          if (request.method === 'POST' && url.pathname === '/threads') {
            const result=await context.client.rpc(ReviewRpc).read(await request.json());
            for(const thread of result.threads){
              const state=context.data.session.status(thread.sessionID);thread.running=state==='busy'||state==='running'||state?.type==='busy'||state?.type==='running';
              const messages=context.data.session.message.list(thread.sessionID)??[];
              for(const turn of thread.turns){const marker=`[OpenCodeReview thread=${thread.id} turn=${turn.id}]`;
                const user=messages.find(m=>m.type==='user'&&typeof m.text==='string'&&m.text.includes(marker));
                turn.completed=!!user&&!thread.running&&messages.some(m=>m.type==='idle'&&m.time?.created>user.time?.created);
              }
            }
            return Response.json(result);
          }
          if (request.method === 'GET' && url.pathname.startsWith('/receipt/')) return Response.json(publicReceipt(await bridge.checkReceipt(url.pathname.slice(9))));
          if(request.method==='POST'&&url.pathname==='/send')return Response.json(publicReceipt(await bridge.send(await request.json())));
          if (request.method === 'POST' && url.pathname === '/append') {
            const input=await request.json();
            const old=bridge.receipt(input.id);if(old)return Response.json(bridge.append(input));
            if(input.turns){
              if(bridge.status().sessionID!==input.sessionID)throw new Error('В терминале сменилась сессия.');
              await context.client.rpc(ReviewRpc).prepare({sessionID:input.sessionID,turns:input.turns});
            }
            return Response.json(bridge.append(input));
          }
          return Response.json({error:'Not found'}, {status:404});
        } catch(error) { return Response.json({error:error.message}, {status:409}); }
      },
    });
    const file = join(directory, `${process.pid}-${bridge.status().instance}.json`);
    const tmp = file + '.tmp';
    writeFileSync(tmp, JSON.stringify({protocol:1, pid:process.pid, instance:bridge.status().instance, port:server.port, token}), {mode:0o600});
    renameSync(tmp,file);
    return () => { server.stop(true); try { unlinkSync(file); } catch {} };
  },
});
