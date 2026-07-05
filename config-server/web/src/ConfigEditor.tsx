import { useEffect, useState } from "react";
import { api, AvailableModule, MirrorConfig, ModuleConfig, REGIONS } from "./api";
import { ComplimentsEditor } from "./ComplimentsEditor";
import { SecurityCard } from "./SecurityCard";
import { StoreCard } from "./StoreCard";
import { UpdatesCard } from "./UpdatesCard";
import { Mark } from "./Logo";

type Section = "global" | "modules" | "store" | "updates" | "security";

export function ConfigEditor({ onLogout }: { onLogout: () => void }) {
  const [config, setConfig] = useState<MirrorConfig | null>(null);
  const [available, setAvailable] = useState<AvailableModule[]>([]);
  const [ips, setIps] = useState<string[]>([]);
  const [version, setVersion] = useState("");
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [section, setSection] = useState<Section>("global");
  const [moduleTab, setModuleTab] = useState(0);

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
  const removeModule = (i: number) => {
    update({ modules: config.modules.filter((_, j) => j !== i) });
    // Keep the selected tab in range after the list shrinks.
    setModuleTab((t) => (t >= i && t > 0 ? t - 1 : t));
  };

  function addModule(name: string) {
    const def = available.find((a) => a.name === name)?.defaultConfig;
    const mod: ModuleConfig = def ?? { module: name, position: "top_left", refreshInterval: 0, config: {} };
    update({ modules: [...config!.modules, { ...mod, config: { ...mod.config } }] });
    setModuleTab(config!.modules.length); // focus the newly added module
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

  const NAV: { id: Section; label: string; sub: string; badge?: number }[] = [
    { id: "global", label: "Global", sub: "Language, time, and units applied across the mirror." },
    { id: "modules", label: "Modules", sub: "Add, place, and configure what the mirror shows.", badge: config.modules.length },
    { id: "store", label: "Store", sub: "Browse and install plugins to add new modules." },
    { id: "updates", label: "Updates", sub: "Check for and install app updates." },
    { id: "security", label: "Security", sub: "Change the admin password." },
  ];
  const current = NAV.find((n) => n.id === section)!;
  // Clamp on render so a stale index (after a remove/reload) never reads out of
  // bounds; -1 only when there are no modules, which the empty state handles.
  const activeIdx = Math.min(moduleTab, config.modules.length - 1);

  // Save only mutates the mirror config (Global + Modules). The footer stays
  // visible on every section so unsaved edits can always be committed, but the
  // save-related state only means anything for the config sections.
  const showFooter = dirty || saving || !!status || !!error || section === "global" || section === "modules";

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="side-brand">
          <Mark size={30} />
          <span className="side-lockup">
            <span className="wm">Specul<span className="wordmark-u">u</span>m</span>
            <span className="side-tag">config console</span>
          </span>
        </div>

        <nav className="side-nav" aria-label="Settings sections">
          {NAV.map((n) => (
            <button
              key={n.id}
              type="button"
              className={"nav-item" + (n.id === section ? " active" : "")}
              aria-current={n.id === section ? "page" : undefined}
              onClick={() => setSection(n.id)}
            >
              <span className="nav-dot" aria-hidden="true" />
              <span className="nav-label">{n.label}</span>
              {n.badge != null && <span className="nav-badge">{n.badge}</span>}
            </button>
          ))}
        </nav>

        <div className="side-foot">
          {version && <span className="side-ver" title="Running app version">v{version}</span>}
          <button
            className="ghost small"
            onClick={() => {
              if (dirty && !confirm("You have unsaved changes. Sign out anyway?")) return;
              onLogout();
            }}
          >
            Sign out
          </button>
        </div>
      </aside>

      <main className="main">
        <div className="main-head">
          <h1 className="main-title">{current.label}</h1>
          <p className="main-sub">{current.sub}</p>
        </div>

        <div className="main-body" key={section}>
          {section === "global" && (
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
          )}

          {section === "modules" && (
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

              {config.modules.length === 0 ? (
                <p className="muted">No modules. Add one above.</p>
              ) : (
                <>
                  <div className="tabs" role="tablist" aria-label="Modules">
                    {config.modules.map((m, i) => (
                      <button
                        key={i}
                        type="button"
                        role="tab"
                        aria-selected={i === activeIdx}
                        className={"tab" + (i === activeIdx ? " active" : "")}
                        onClick={() => setModuleTab(i)}
                      >
                        {m.module}
                      </button>
                    ))}
                  </div>

                  <ModuleCard
                    key={activeIdx}
                    mod={config.modules[activeIdx]}
                    ips={ips}
                    onChange={(x) => updateModule(activeIdx, x)}
                    onRemove={() => removeModule(activeIdx)}
                  />
                </>
              )}
            </section>
          )}

          {section === "store" && <StoreCard onChanged={reloadAfterStore} />}

          {section === "updates" && <UpdatesCard />}

          {section === "security" && <SecurityCard />}
        </div>

        {showFooter && (
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
        )}
      </main>
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