import { createHash, randomUUID } from 'node:crypto';
export const digest = text => createHash('sha256').update(text).digest('hex');
export function createBridge(context, hooks={}) {
  const inFlight=new Map();
  let sending=false;
  const receipts = new Map();
  const instance = randomUUID();
  function target() {
    const route = context.ui.router.current();
    const field = context.renderer.currentFocusedRenderable;
    if (route?.type !== 'session' || !route.sessionID) throw new Error('Открой существующую сессию OpenCode.');
    // Version adapter for OpenCode 2.2.5: use the same input and insertText operation as tui.prompt.append.
    // Check semantic OpenTUI traits, not a host component ID or a guessed screen position.
    if (!field || field.isDestroyed || field.focusable === false || field.traits?.owner !== 'opencode'
        || field.traits?.role !== 'prompt' || field.traits?.status === 'SHELL'
        || typeof field.plainText !== 'string' || typeof field.insertText !== 'function'
        || typeof field.gotoBufferEnd !== 'function') throw new Error('Вернись в обычное поле сообщения OpenCode и закрой диалоги.');
    return {route, field};
  }
  function status() {
    let ready = false, error = '';
    try { target(); ready = true; } catch (e) { error = e.message; }
    const route = context.ui.router.current();
    const session = route?.sessionID ? context.data.session.get(route.sessionID) : null;
    let draftText="";try{draftText=target().field.plainText;}catch{}
    return { send:typeof context.keymap?.dispatch==='function', draftText, draftHash:digest(draftText), protocol: 1, instance, pid: process.pid, version: context.app.version, ready, error,
      sessionID: route?.sessionID ?? '', title: session?.title ?? '', directory: session?.location?.directory ?? context.location?.directory ?? context.data.location.default()?.directory ?? process.cwd() };
  }
  function append(request) {
    if (!request || typeof request.id !== 'string' || !/^[a-zA-Z0-9-]{8,100}$/.test(request.id)
        || typeof request.text !== 'string' || !request.text.trim() || request.text.length > 100000
        || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(request.text)) throw new Error('Некорректная пачка комментариев.');
    const hash = digest(request.text);
    if (request.hash !== hash || request.instance !== instance) throw new Error('Получатель или текст пачки изменился.');
    const existing = receipts.get(request.id);
    if (existing) {
      if (existing.hash !== hash) throw new Error('ID переноса уже использован с другим текстом.');
      if (!existing.ok) throw new Error(existing.error);
      return existing;
    }
    if (receipts.size >= 512) throw new Error('Лимит переносов этого клиента достигнут; перезапусти TUI.');
    const {route, field} = target();
    if (route.sessionID !== request.sessionID) throw new Error('В терминале сменилась сессия. Повтори перенос.');
    const before = field.plainText;
    // Do not turn an existing slash command or shell command into an accidental executable instruction.
    if (before.trimStart().startsWith('/') || before.trimStart().startsWith('!')) throw new Error('В поле набрана команда. Убери её перед переносом комментариев.');
    const suffix = (before.length ? '\n\n' : '') + request.text;
    if (before.length + suffix.length > 200000) throw new Error('Поле сообщения слишком большое.');
    const uncertain = {ok:false, id:request.id, hash, instance, sessionID:route.sessionID,
      error:'Не удалось подтвердить полный текст в поле; очередь сохранена. Проверь поле OpenCode перед повтором.'};
    receipts.set(request.id, uncertain);
    field.gotoBufferEnd();
    field.insertText(suffix);
    if (field.plainText !== before + suffix) throw new Error(uncertain.error);
    context.renderer.requestRender();
    const receipt = {ok:true, id:request.id, hash, instance, sessionID:route.sessionID, inserted:request.text.length};
    receipts.set(request.id, receipt);
    return receipt;
  }
  function validateSend(request) {
    if(!request||typeof request.id!=='string'||!/^[a-zA-Z0-9-]{8,100}$/.test(request.id)
        ||typeof request.text!=='string'||!request.text.trim()||request.text.length>100000
        ||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(request.text)
        ||request.hash!==digest(request.text)||request.instance!==instance)throw new Error('Некорректная отправка или получатель.');
    const old=receipts.get(request.id);
    if(old){if(old.hash!==request.hash||old.sessionID!==request.sessionID||!old.submit)throw new Error('ID уже использован для другой операции.');return old;}
    if(receipts.size>=512)throw new Error('Лимит отправок достигнут; перезапусти TUI.');
    if(typeof context.keymap?.dispatch!=='function')throw new Error('Эта версия OpenCode не поддерживает отправку.');
    const {route,field}=target();
    if(route.sessionID!==request.sessionID)throw new Error('В терминале сменилась сессия. Открой предпросмотр снова.');
    if(digest(field.plainText)!==request.draftHash)throw new Error('Черновик терминала изменился. Открой предпросмотр снова.');
    if(field.plainText.trimStart().startsWith('/')||field.plainText.trimStart().startsWith('!'))throw new Error('Убери команду из поля OpenCode перед отправкой.');
    if(field.plainText.length+request.text.length+2>200000)throw new Error('Поле сообщения слишком большое.');
    return null;
  }
  async function reconcile(receipt) {
    if(receipt?.submit&&!receipt.ok&&await hooks.confirm?.(receipt)){
      receipt.ok=true;receipt.submitted=true;delete receipt.error;
    }
    return receipt;
  }
  function send(request) {
    try{const old=validateSend(request);if(old)return inFlight.get(request.id)??reconcile(old);}catch(error){return Promise.reject(error);}
    const pending=inFlight.get(request.id);if(pending){if(pending.requestHash!==request.hash||pending.sessionID!==request.sessionID)return Promise.reject(new Error('ID уже используется для другого текста.'));return pending;}
    if(sending)return Promise.reject(new Error('Предыдущая отправка ещё выполняется.'));
    sending=true;
    const job=(async()=>{
      await hooks.prepare?.(request);
      validateSend(request); // await may have changed focus, session or draft
      const {field}=target();const before=field.plainText;
      const after=before+(before.length?'\n\n':'')+request.text;
      const receipt={ok:false,submit:true,submitted:false,id:request.id,hash:request.hash,instance,sessionID:request.sessionID,
        turns:request.turns, error:'Отправка не подтверждена. Проверь OpenCode; повторная вставка заблокирована.'};
      receipts.set(request.id,receipt); // persist uncertainty before touching the native composer
      try{
        field.gotoBufferEnd();field.insertText(after.slice(before.length));
        if(field.plainText!==after)throw new Error(receipt.error);
        context.renderer.requestRender();
        await context.keymap.dispatch('prompt.submit');
        // A cleared input is not proof: native submission can roll back on server rejection.
        for(let attempt=0;attempt<32;attempt++){
          await reconcile(receipt);if(receipt.ok)break;
          await new Promise(resolve=>setTimeout(resolve,200));
        }
      }catch(error){receipt.error=error.message;}
      return receipt;
    })().finally(()=>{sending=false;inFlight.delete(request.id);});
    job.requestHash=request.hash;job.sessionID=request.sessionID;inFlight.set(request.id,job);return job;
  }
  return { status, append, send, receipt: id => receipts.get(id) ?? null,
    checkReceipt:async id=>{const r=receipts.get(id);return r?reconcile(r):null;} };

}
