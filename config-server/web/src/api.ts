// Typed client for the config-server REST API. Token kept in localStorage.

export interface ModuleConfig {
  module: string;
  position: string;
  refreshInterval: number; // ms (JSON key matches @SerialName)
  config: Record<string, string>;
}

export interface MirrorConfig {
  language: string;
  timeFormat: number;
  units: string;
  modules: ModuleConfig[];
}

export interface AvailableModule {
  name: string;
  order: number;
  defaultConfig: ModuleConfig | null;
}

export interface UpdateStatus {
  currentVersion: string;
  latestVersion: string | null;
  updateAvailable: boolean;
  updatable: boolean;
  installType: string;
  signed: boolean;
  releaseUrl: string | null;
  reason: string | null;
}

export interface UpdateProgress {
  // idle | downloading | verifying | installing | restarting | installed | error
  phase: string;
  pct: number | null;
  message: string;
  targetVersion: string | null;
}

export interface StorePlugin {
  id: string;
  name: string;
  description: string;
  author: string;
  homepage: string;
  moduleName: string;
  installed: boolean;
  enabled: boolean;
}

export interface StoreView {
  plugins: StorePlugin[];
  reason: string | null;
}

const TOKEN_KEY = "mm_token";

export const getToken = () => localStorage.getItem(TOKEN_KEY);
export const setToken = (t: string) => localStorage.setItem(TOKEN_KEY, t);
export const clearToken = () => localStorage.removeItem(TOKEN_KEY);

async function req<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init.headers as Record<string, string>),
  };
  const token = getToken();
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) {
    clearToken();
    throw new Error("Unauthorized");
  }
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || res.statusText);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const api = {
  login: (password: string) =>
    req<{ token: string }>("/api/login", { method: "POST", body: JSON.stringify({ password }) }),
  getConfig: () => req<MirrorConfig>("/api/config"),
  saveConfig: (config: MirrorConfig) =>
    req<{ message: string }>("/api/config", { method: "PUT", body: JSON.stringify(config) }),
  getModules: () => req<AvailableModule[]>("/api/modules"),
  getIps: () => req<string[]>("/api/ips"),
  getVersion: () => req<{ version: string }>("/api/version"),
  getUpdateStatus: () => req<UpdateStatus>("/api/update/status"),
  startUpdate: () => req<UpdateProgress>("/api/update/start", { method: "POST" }),
  getUpdateProgress: () => req<UpdateProgress>("/api/update/progress"),
  changePassword: (currentPassword: string, newPassword: string) =>
    req<{ message: string }>("/api/password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    }),
  getStore: () => req<StoreView>("/api/store"),
  installPlugin: (id: string) =>
    req<StoreView>("/api/store/install", { method: "POST", body: JSON.stringify({ id }) }),
  uninstallPlugin: (id: string) =>
    req<StoreView>("/api/store/uninstall", { method: "POST", body: JSON.stringify({ id }) }),
};

export const REGIONS = [
  "top_left", "top_center", "top_right",
  "upper_third", "middle_center", "lower_third",
  "bottom_left", "bottom_center", "bottom_right",
  "bottom_bar", "fullscreen_above", "fullscreen_below",
];