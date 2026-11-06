#!/usr/bin/env python3
"""Install the IDEA plugin and OpenCode v2 TUI bridge; keep existing settings and backups."""
from pathlib import Path
import argparse,datetime,json,os,re,shutil,sys,zipfile,hashlib

def jsonc(text):
    # JSONC comments and trailing commas, preserving quoted strings.
    clean=re.sub(r'"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\r\n]*',lambda m:m[0] if m[0].startswith('"') else '',text)
    clean=re.sub(r'("(?:\\.|[^"\\])*"\s*)|,\s*([}\]])',lambda m:m[1] if m[1] is not None else m[2],clean)
    return json.loads(clean)

def install(root,home,idea_config):
    stamp=datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backups=[]
    def write(path,data):
        if path.exists() and path.read_bytes()==data:return
        path.parent.mkdir(parents=True,exist_ok=True)
        if path.exists():
            backup_dir=home/'.local/share/opencode-review/backups'/stamp;backup_dir.mkdir(parents=True,exist_ok=True,mode=0o700)
            backup=backup_dir/(hashlib.sha256(str(path).encode()).hexdigest()[:10]+'-'+path.name);shutil.copy2(path,backup);backups.append(str(backup))
        temp=path.with_name(path.name+'.opencode-review.tmp');temp.write_bytes(data);temp.chmod(0o600);os.replace(temp,path)
    config_root=Path(os.environ.get('XDG_CONFIG_HOME',str(home/'.config')))/'opencode'
    # --home is for isolated installation tests and portable/custom profiles.
    if home!=Path.home():config_root=home/'.config/opencode'
    cfg=config_root/('cli.jsonc' if (config_root/'cli.jsonc').exists() else 'cli.json')
    settings=jsonc(cfg.read_text()) if cfg.exists() else {}
    if not isinstance(settings,dict) or not isinstance(settings.get('plugins',[]),list):raise ValueError('Expected an object with a plugins array in '+str(cfg))
    bridge=home/'.local/share/opencode-review/bridge'
    uri=bridge.resolve().as_uri()
    def ours(x):return x==uri or x==str(bridge) or isinstance(x,dict) and x.get('package') in [uri,str(bridge)]
    # The server package now loads its TUI part itself. Remove only our old CLI-only entry.
    settings['plugins']=[x for x in settings.get('plugins',[]) if not ours(x)]
    server_cfg=config_root/('opencode.jsonc' if (config_root/'opencode.jsonc').exists() else 'opencode.json')
    server_settings=jsonc(server_cfg.read_text()) if server_cfg.exists() else {}
    if not isinstance(server_settings,dict) or not isinstance(server_settings.get('plugins',[]),list):raise ValueError('Expected plugins array in '+str(server_cfg))
    plugins=list(server_settings.get('plugins',[]))
    if not any(ours(x) for x in plugins):plugins.append(uri)
    server_settings['plugins']=plugins
    archive=(root/'dist' if (root/'dist').is_dir() else root)/'opencode-review-3.0.0.zip'
    with zipfile.ZipFile(archive) as z:
        jar=z.read('opencode-review/lib/opencode-review.jar')
    # Validate everything before changing any destination.
    import io
    with zipfile.ZipFile(io.BytesIO(jar)) as j:
        if '<id>dev.opencodereview</id>' not in j.read('META-INF/plugin.xml').decode():raise ValueError('Unexpected IDEA plugin identity')
    for name in ['package.json','tui.js','protocol.mjs','rpc.js','threads.mjs','server.js']:
        write(bridge/name,(root/'opencode'/name).read_bytes())
    if cfg.exists():write(cfg,(json.dumps(settings,ensure_ascii=False,indent=2)+'\n').encode())
    write(server_cfg,(json.dumps(server_settings,ensure_ascii=False,indent=2)+'\n').encode())
    write(idea_config/'plugins/opencode-review/lib/opencode-review.jar',jar)
    disabled=idea_config/'disabled_plugins.txt'
    ids=disabled.read_text().splitlines() if disabled.exists() else []
    ids=[s for s in ids if s.strip()!='dev.opencodereview']
    if 'dev.bbidea.draft.prototype' not in ids:ids.append('dev.bbidea.draft.prototype')
    write(disabled,('\n'.join(ids)+'\n').encode())
    print('OpenCode Review 3.0.0 installed')
    print('IDEA: '+str(idea_config))
    print('OpenCode bridge: '+str(bridge))
    print('Existing settings retained; backups: '+str(len(backups)))
    if backups:
        manifest=home/'.local/share/opencode-review/backups'/stamp/'files.json'
        manifest.write_text(json.dumps(backups,ensure_ascii=False,indent=2))
    print('Restart IDEA, the OpenCode server and existing OpenCode terminal clients to load review_reply. Open View > Tool Windows > OpenCode Review.')
    return server_cfg,bridge

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home',type=Path,default=Path.home())
    parser.add_argument('--idea-config',type=Path)
    args=parser.parse_args()
    if args.idea_config is None:
        if sys.platform=='darwin':base=args.home/'Library/Application Support/JetBrains'
        elif os.name=='nt':base=Path(os.environ.get('APPDATA',str(args.home/'AppData/Roaming')))/'JetBrains'
        else:base=Path(os.environ.get('XDG_CONFIG_HOME',str(args.home/'.config')))/'JetBrains'
        candidates=sorted(base.glob('IntelliJIdea2026.3*'))
        if not candidates:raise SystemExit('IDEA 2026.3 configuration not found. Run it once, or use --idea-config PATH.')
        args.idea_config=candidates[-1]
    script_root=Path(__file__).resolve().parent
    install(script_root.parent if (script_root.parent/"intellij").is_dir() else script_root,args.home,args.idea_config)
if __name__=='__main__':main()
