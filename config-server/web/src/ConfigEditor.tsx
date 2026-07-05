import { useEffect, useState } from "react";
import { api, AvailableModule, MirrorConfig, ModuleConfig, REGIONS } from "./api";
import { ComplimentsEditor } from "./ComplimentsEditor";
import { SecurityCard } from "./SecurityCard";
import { StoreCard } from "./StoreCard";
import { UpdatesCard } from "./UpdatesCard";
import { Mark } from "./Logo";

export function ConfigEditor({ onLogout }: { onLogout: () => void }) {
  const [config, setConfig] = useState<MirrorConfig | null>(null);
  const [available, setAvailable] = useState<AvailableModule[]>([]);
  const [ips, setIps] = useState<string[]>([]);
  const [version, setVersion] = useState("");
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  function reload() {
    Promise.all([api.getConfig(), api.getModules(), api.getIps(), api.getVersion()])
      .then(([c, m, ip, v]) => {
        setConfig(c);
        setAvailable(m);
        setIps(ip);
        setVersion(v.version);
      })
      .catch((e) => (e.message === "Unauthorized" ? onLogout() : setError(e.message)));
  }

  useEffect(() => {
    reload();
  }, []);

  // A store install/uninstall changes config + modules server-side; re-fetch.
  // Skipped (with a warning) when there are unsaved edits to avoid clobbering them.
  function reloadAfterStore() {
    if (dirty && !confirm("Reload will discard your unsaved changes. Continue?")) return;
    setDirty(false);
    reload();
  }

  // Warn before the tab is closed/reloaded with unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  if (error && !config) return <div className="center error">{error}</div>;
  if (!config) return <div className="center muted">Loading…</div>;

  const update = (patch: Partial<MirrorConfig>) => {
    setConfig({ ...config, ...patch });
    setDirty(true);
    if (status) setStatus(""); // a stale "Saved" no longer reflects the form
  };
  const updateModule = (i: number, m: ModuleConfig) =>
    update({ modules: config.modules.map((x, j) => (j === i ? m : x)) });
  const removeModule = (i: number) =>
    update({ modules: config.modules.filter((_, j) => j !== i) });

  function addModule(name: string) {
    const def = available.find((a) => a.name === name)?.defaultConfig;
    const mod: ModuleConfig = def ?? { module: name, position: "top_left", refreshInterval: 0, config: {} };
    update({ modules: [...config!.modules, { ...mod, config: { ...mod.config } }] });
  }

  async function save() {
    setSaving(true);
    setStatus("Saving…");
    setError("");
    try {
      const res = await api.saveConfig(config!);
      setStatus(res.message);
      setDirty(false);
    } catch (e: any) {
      setError(e.message);
      setStatus("");
    } finally {
      setSaving(false);
    }
  }

  const notAdded = available.filter((a) => !config.modules.some((m) => m.module === a.name));

  return (
    <div className="page">
      <header>
        <div className="brand">
          <Mark size={34} />
          <span className="wm">Specul<span className="wordmark-u">u</span>m</span>
          <span className="tag">config</span>
          {version && <span className="tag" title="Running app version">v{version}</span>}
        </div>
        <span className="spacer" />
        <button
          className="ghost"
          onClick={() => {
            if (dirty && !confirm("You have unsaved changes. Sign out anyway?")) return;
            onLogout();
          }}
        >
          Sign out
        </button>
      </header>

      <section className="card">
        <h2>Global</h2>
        <div className="row">
          <label>Language
            <input value={config.language} onChange={(e) => update({ language: e.target.value })} />
          </label>
          <label>Time format
            <select value={config.timeFormat} onChange={(e) => update({ timeFormat: Number(e.target.value) })}>
              <option value={24}>24h</option>
              <option value={12}>12h</option>
            </select>
          </label>
          <label>Units
            <select value={config.units} onChange={(e) => update({ units: e.target.value })}>
              <option value="metric">metric</option>
              <option value="imperial">imperial</option>
            </select>
          </label>
        </div>
      </section>

      <section className="card">
        <div className="card-head">
          <h2>Modules ({config.modules.length})</h2>
          {notAdded.length > 0 && (
            <select value="" onChange={(e) => e.target.value && addModule(e.target.value)}>
              <option value="">+ Add module…</option>
              {notAdded.map((a) => <option key={a.name} value={a.name}>{a.name}</option>)}
            </select>
          )}
        </div>

        {config.modules.map((m, i) => (
          <ModuleCard
            key={i}
            mod={m}
            ips={ips}
            onChange={(x) => updateModule(i, x)}
            onRemove={() => removeModule(i)}
          />
        ))}
        {config.modules.length === 0 && <p className="muted">No modules. Add one above.</p>}
      </section>

      <StoreCard onChanged={reloadAfterStore} />

      <UpdatesCard />

      <SecurityCard />

      <footer>
        <button onClick={save} disabled={saving || !dirty}>
          {saving ? "Saving…" : "Save"}
        </button>
        <span className="foot-status" aria-live="polite">
          {error ? (
            <span className="error">{error}</span>
          ) : status ? (
            <span className="ok">{status}</span>
          ) : dirty ? (
            <span className="pending muted small">Unsaved changes</span>
          ) : null}
        </span>
        <span className="spacer" />
        <span className="muted small">The mirror reloads automatically on save.</span>
      </footer>
    </div>
  );
}

function ModuleCard({
  mod, ips, onChange, onRemove,
}: { mod: ModuleConfig; ips: string[]; onChange: (m: ModuleConfig) => void; onRemove: () => void }) {
  const isCompliments = mod.module === "compliments";
  const isQr = mod.module === "qr";
  // Special editors replace the raw key-value rows for these keys.
  const hidden = (k: string) =>
    (isCompliments && k === "compliments") || (isQr && (k === "ip" || k === "size"));
  const entries = Object.entries(mod.config).filter(([k]) => !hidden(k));

  const setKey = (key: string, value: string) =>
    onChange({ ...mod, config: { ...mod.config, [key]: value } });

  const setConfigKey = (oldKey: string, newKey: string, value: string) => {
    const next: Record<string, string> = {};
    for (const [k, v] of Object.entries(mod.config)) next[k === oldKey ? newKey : k] = k === oldKey ? value : v;
    onChange({ ...mod, config: next });
  };
  const removeKey = (key: string) => {
    const next = { ...mod.config };
    delete next[key];
    onChange({ ...mod, config: next });
  };
  const addKey = () => onChange({ ...mod, config: { ...mod.config, "": "" } });

  return (
    <div className="module">
      <div className="row">
        <strong className="modname">{mod.module}</strong>
        <label>Position
          <select value={mod.position} onChange={(e) => onChange({ ...mod, position: e.target.value })}>
            {REGIONS.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </label>
        <label>Refresh (ms)
          <input type="number" min={0} step={1000} value={mod.refreshInterval}
            onChange={(e) => onChange({ ...mod, refreshInterval: Number(e.target.value) })} />
        </label>
        <button className="ghost danger" onClick={onRemove}>Remove</button>
      </div>

      <div className="kv">
        {entries.map(([k, v], idx) => (
          <div className="kv-row" key={idx}>
            <input className="k" placeholder="key" value={k}
              onChange={(e) => setConfigKey(k, e.target.value, v)} />
            <input className="v" placeholder="value" value={v}
              onChange={(e) => setConfigKey(k, k, e.target.value)} />
            <button className="ghost danger" aria-label="Remove option" title="Remove option" onClick={() => removeKey(k)}>×</button>
          </div>
        ))}
        <button className="ghost small" onClick={addKey}>+ option</button>
      </div>

      {isCompliments && (
        <ComplimentsEditor
          value={mod.config["compliments"] ?? ""}
          onChange={(v) => onChange({ ...mod, config: { ...mod.config, compliments: v } })}
        />
      )}

      {isQr && (() => {
        const ip = mod.config["ip"] ?? "";
        const port = mod.config["port"] || "8080";
        const target = mod.config["url"] || `http://${ip || "<auto LAN IP>"}:${port}`;
        return (
          <div className="qr">
            <div className="row">
              <label>QR target IP
                <select value={ip} onChange={(e) => setKey("ip", e.target.value)}>
                  <option value="">Auto-detect (LAN)</option>
                  {ips.map((x) => <option key={x} value={x}>{x}</option>)}
                  {ip && !ips.includes(ip) && <option value={ip}>{ip} (custom)</option>}
                </select>
              </label>
              <label>Size (dp)
                <input type="number" min={60} max={400} step={10}
                  value={mod.config["size"] || "110"}
                  onChange={(e) => setKey("size", e.target.value)} />
              </label>
            </div>
            <div className="muted small">Encodes: <code>{target}</code></div>
          </div>
        );
      })()}
    </div>
  );
}