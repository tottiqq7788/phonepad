import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import QRCode from "qrcode";
import { useEffect, useMemo, useState } from "react";

type ReceiverStatus = {
  running: boolean;
  udpPort: number;
  tcpPort: number;
  discoveryPort: number;
  packetsReceived: number;
  packetsDropped: number;
  movePackets: number;
  scrollPackets: number;
  clickPackets: number;
  lastClient?: string;
  lastRttMs?: number;
};

type PairingInfo = {
  localIp: string;
  recommendedIp: string;
  allIps: string[];
  udpPort: number;
  tcpPort: number;
  discoveryPort: number;
  connectionUrl: string;
};

type Settings = {
  sensitivity: number;
  acceleration: number;
  scrollSensitivity: number;
  lowLatencyMode: boolean;
};

const defaultSettings: Settings = {
  sensitivity: 1.0,
  acceleration: 0.18,
  scrollSensitivity: 1.0,
  lowLatencyMode: true,
};

export default function App() {
  const [status, setStatus] = useState<ReceiverStatus | null>(null);
  const [pairing, setPairing] = useState<PairingInfo | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string>("");
  const [settings, setSettings] = useState<Settings>(defaultSettings);
  const [error, setError] = useState<string | null>(null);

  const packetRate = useMemo(() => {
    if (!status) return 0;
    return status.movePackets + status.scrollPackets + status.clickPackets;
  }, [status]);

  async function refreshStatus() {
    const next = await invoke<ReceiverStatus>("receiver_status");
    setStatus(next);
  }

  async function refreshPairing() {
    const next = await invoke<PairingInfo>("pairing_info");
    setPairing(next);
    if (!next.recommendedIp) {
      setQrDataUrl("");
      return;
    }
    const dataUrl = await QRCode.toDataURL(next.connectionUrl, {
      margin: 1,
      width: 180,
      color: {
        dark: "#0a0c10",
        light: "#ffffff",
      },
    });
    setQrDataUrl(dataUrl);
  }

  async function startReceiver() {
    setError(null);
    try {
      const next = await invoke<ReceiverStatus>("start_receiver", { settings });
      setStatus(next);
      await refreshPairing();
    } catch (err) {
      setError(String(err));
    }
  }

  async function stopReceiver() {
    setError(null);
    try {
      const next = await invoke<ReceiverStatus>("stop_receiver");
      setStatus(next);
    } catch (err) {
      setError(String(err));
    }
  }

  async function updateSettings(next: Settings) {
    setSettings(next);
    try {
      const updated = await invoke<ReceiverStatus>("update_settings", { settings: next });
      setStatus(updated);
    } catch (err) {
      setError(String(err));
    }
  }

  useEffect(() => {
    refreshStatus().catch((err) => setError(String(err)));
    refreshPairing().catch((err) => setError(String(err)));
    startReceiver().catch((err) => setError(String(err)));

    const timer = window.setInterval(() => {
      refreshStatus().catch(() => undefined);
    }, 1000);

    let unlisten: (() => void) | undefined;
    listen<ReceiverStatus>("receiver://status", (event) => setStatus(event.payload)).then((fn) => {
      unlisten = fn;
    });

    return () => {
      window.clearInterval(timer);
      unlisten?.();
    };
  }, []);

  const running = status?.running ?? false;
  const recommendedIp = pairing?.recommendedIp || pairing?.localIp || "";
  const displayIp = recommendedIp || "未检测到可用网络";

  return (
    <main className="shell">
      <header className="console-header">
        <div>
          <p className="console-header__title">PhonePad Receiver</p>
          <h1>低延迟手机触控板接收端</h1>
          <p className="console-header__subtitle">
            监听 UDP 输入包，优先保证跟手。手机端可通过局域网发现、扫码或手动输入 IP 连接。
          </p>
        </div>
        <div className="header-actions">
          <span className={`status-pill ${running ? "status-pill--running" : "status-pill--stopped"}`}>
            <span className="status-dot" />
            {running ? "正在接收" : "未启动"}
          </span>
          <button
            type="button"
            className={`btn ${running ? "btn--danger" : "btn--primary"}`}
            onClick={running ? stopReceiver : startReceiver}
          >
            {running ? "停止接收" : "启动接收"}
          </button>
        </div>
      </header>

      {error ? <div className="error">{error}</div> : null}

      <section className="console-grid">
        <article className="panel">
          <h2>连接状态</h2>
          <dl className="meta-grid">
            <dt>推荐 IP</dt>
            <dd className="highlight">{displayIp}</dd>
            <dt>最近客户端</dt>
            <dd>{status?.lastClient ?? "等待连接"}</dd>
            <dt>最近 RTT</dt>
            <dd>{status?.lastRttMs != null ? `${status.lastRttMs.toFixed(1)} ms` : "—"}</dd>
            <dt>UDP / TCP / 发现</dt>
            <dd>
              {status?.udpPort ?? 45454} / {status?.tcpPort ?? 45455} / {status?.discoveryPort ?? 45456}
            </dd>
            <dt>全部地址</dt>
            <dd>{pairing?.allIps?.join(", ") ?? "检测中"}</dd>
          </dl>
        </article>

        <article className="panel">
          <h2>配对</h2>
          <p className="panel__desc">Android 端扫描二维码，或手动输入下方地址。</p>
          <div className="pairing-panel">
            {qrDataUrl ? <img className="qr-image" src={qrDataUrl} alt="配对二维码" /> : null}
            <div className="pairing-details">
              <code className="pairing-code">{pairing?.connectionUrl || "未检测到可用网络，请检查 Wi-Fi 连接"}</code>
              <p className="hint">推荐手动输入：{displayIp}</p>
              {(pairing?.allIps?.length ?? 0) > 1 ? (
                <p className="hint">检测到多个网卡，请优先选择与手机同网段的地址。</p>
              ) : null}
            </div>
          </div>
        </article>

        <article className="panel">
          <h2>输入统计</h2>
          <div className="stats-row">
            <div className="stat-box">
              <span className="stat-box__label">总包数</span>
              <span className="stat-box__value">{status?.packetsReceived ?? 0}</span>
            </div>
            <div className="stat-box">
              <span className="stat-box__label">丢弃</span>
              <span className="stat-box__value">{status?.packetsDropped ?? 0}</span>
            </div>
            <div className="stat-box">
              <span className="stat-box__label">输入事件</span>
              <span className="stat-box__value">{packetRate}</span>
            </div>
          </div>
          <dl className="meta-grid">
            <dt>移动</dt>
            <dd>{status?.movePackets ?? 0}</dd>
            <dt>滚动</dt>
            <dd>{status?.scrollPackets ?? 0}</dd>
            <dt>点击</dt>
            <dd>{status?.clickPackets ?? 0}</dd>
          </dl>
        </article>

        <article className="panel">
          <h2>手感设置</h2>
          <label className="slider-field">
            灵敏度
            <input
              type="range"
              min="0.3"
              max="3"
              step="0.05"
              value={settings.sensitivity}
              onChange={(event) => updateSettings({ ...settings, sensitivity: Number(event.target.value) })}
            />
            <span>{settings.sensitivity.toFixed(2)}</span>
          </label>
          <label className="slider-field">
            加速度
            <input
              type="range"
              min="0"
              max="0.7"
              step="0.01"
              value={settings.acceleration}
              onChange={(event) => updateSettings({ ...settings, acceleration: Number(event.target.value) })}
            />
            <span>{settings.acceleration.toFixed(2)}</span>
          </label>
          <label className="slider-field">
            滚动速度
            <input
              type="range"
              min="0.2"
              max="3"
              step="0.05"
              value={settings.scrollSensitivity}
              onChange={(event) => updateSettings({ ...settings, scrollSensitivity: Number(event.target.value) })}
            />
            <span>{settings.scrollSensitivity.toFixed(2)}</span>
          </label>
        </article>
      </section>

      <section className="notice">
        <strong>macOS：</strong>需在「系统设置 → 隐私与安全性 → 辅助功能」中允许本应用控制电脑。
        <br />
        <strong>Windows：</strong>首次启动请允许 UDP 45454 / TCP 45455 / UDP 45456 通过防火墙。
      </section>
    </main>
  );
}
