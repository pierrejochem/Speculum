import { useEffect, useState } from "react";
import { api, StoreView } from "./api";

/**
 * Plugin app store. Lists catalog entries and installs/uninstalls them via the
 * backend, which downloads the JAR into the user plugins dir and enables the
 * module in config. A freshly installed module only renders after the mirror
 * restarts (the loader scans plugins once at boot), so we surface that notice.
 *
 * `onChanged` lets the editor re-fetch config + module list after an install or
 * uninstall changes them server-side.
 */
export function StoreCard({ onChanged }: { onChanged: () => void }) {
  const [view, setView] = useState<StoreView | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [restartNeeded, setRestartNeeded] = useState(false);

  useEffect(() => {
    api.getStore().then(setView).catch((e) => setError(e.message));
  }, []);

  async function run(id: string, action: (id: string) => Promise<StoreView>, restart: boolean) {
    setBusyId(id);
    setError("");
    try {
      setView(await action(id));
      if (restart) setRestartNeeded(true);
      onChanged();
    } catch (e: any) {
      setError(e.message || "Action failed");
    } finally {
      setBusyId(null);
    }
  }

  function body() {
    if (error && !view) return <span className="error small">{error}</span>;
    if (!view) return <span className="muted small">Loading store…</span>;
    if (view.reason) return <span className="muted small">{view.reason}</span>;
    if (view.plugins.length === 0)
      return <span className="muted small">No plugins available yet.</span>;

    return (
      <>
        {view.plugins.map((p) => (
          <div className="store-row" key={p.id}>
            <div className="store-meta">
              <div className="store-title">
                <strong>{p.name}</strong>
                {p.installed && <span className="pill">Installed</span>}
                {p.enabled && <span className="pill pill-on">Enabled</span>}
              </div>
              {p.description && <div className="muted small">{p.description}</div>}
              <div className="muted small">
                {p.author && <span>by {p.author}</span>}
                {p.homepage && (
                  <>
                    {p.author && " · "}
                    <a href={p.homepage} target="_blank" rel="noreferrer">Homepage</a>
                  </>
                )}
              </div>
            </div>
            <div className="store-actions">
              {p.installed ? (
                <button
                  className="ghost danger small"
                  disabled={busyId === p.id}
                  onClick={() => run(p.id, api.uninstallPlugin, false)}
                >
                  {busyId === p.id ? "Removing…" : "Uninstall"}
                </button>
              ) : (
                <button
                  className="small"
                  disabled={busyId === p.id}
                  onClick={() => run(p.id, api.installPlugin, true)}
                >
                  {busyId === p.id ? "Installing…" : "Install"}
                </button>
              )}
            </div>
          </div>
        ))}
        {error && <span className="error small">{error}</span>}
        {restartNeeded && (
          <p className="ok small" aria-live="polite">
            Installed — restart the mirror to load newly installed modules.
          </p>
        )}
      </>
    );
  }

  return (
    <section className="card">
      <h2>Plugin store</h2>
      {body()}
    </section>
  );
}