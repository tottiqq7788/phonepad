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
    if (!status?.packetsReceived) return 0;
    return status.movePackets + status.scrollPackets + status.clickPackets;
  }, [status]);

  async function refreshStatus() {
    const next = await invoke<ReceiverStatus>("receiver_status");
    setStatus(next);
  }

  async function refreshPairing() {
    const next = await invoke<PairingInfo>("pairing_info");
    setPairing(next);
    const dataUrl = await QRCode.toDataURL(next.connectionUrl, {
      margin: 1,
      width: 220,
      color: {
        dark: "#081020",
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

  return (
    <main className="shell">
      <section className="hero">
        <div>
          <p className="eyebrow">PhonePad Receiver</p>
          <h1>低延迟手机触控板接收端</h1>
          <p className="lead">
            桌面端监听 UDP 输入包，Android 端负责触摸采样和震动反馈。移动数据不排队重传，优先保证跟手。
          </p>
        </div>
        <button className={running ? "danger" : "primary"} onClick={running ? stopReceiver : startReceiver}>
          {running ? "停止接收" : "启动接收"}
        </button>
      </section>

      {error ? <div className="error">{error}</div> : null}

      <section className="grid">
        <article className="card">
          <h2>连接</h2>
          <dl>
            <dt>状态</dt>
            <dd>{running ? "运行中" : "未启动"}</dd>
            <dt>推荐 IP</dt>
            <dd>{pairing?.recommendedIp ?? pairing?.localIp ?? "检测中"}</dd>
            <dt>全部地址</dt>
            <dd>{pairing?.allIps?.join(", ") ?? "检测中"}</dd>
            <dt>UDP 输入端口</dt>
            <dd>{status?.udpPort ?? 45454}</dd>
            <dt>TCP 控制端口</dt>
            <dd>{status?.tcpPort ?? 45455}</dd>
            <dt>发现端口</dt>
            <dd>{status?.discoveryPort ?? 45456}</dd>
            <dt>最近客户端</dt>
            <dd>{status?.lastClient ?? "等待连接"}</dd>
            <dt>最近 RTT</dt>
            <dd>{status?.lastRttMs ? `${status.lastRttMs.toFixed(1)} ms` : "暂无"}</dd>
          </dl>
        </article>

        <article className="card pairing-card">
          <h2>配对</h2>
          <p>Android App 会优先通过局域网发现接收端；失败时可扫码或手动输入 IP。</p>
          {qrDataUrl ? <img className="qr-image" src={qrDataUrl} alt="配对二维码" /> : null}
          <code className="pairing-code">{pairing?.connectionUrl ?? "phonepad://desktop-ip:45455"}</code>
          <p className="hint">推荐手动输入：{pairing?.recommendedIp ?? pairing?.localIp ?? "desktop-ip"}</p>
          {(pairing?.allIps?.length ?? 0) > 1 ? (
            <p className="hint">检测到多个网卡，请优先选择与手机同网段的地址。</p>
          ) : null}
        </article>

        <article className="card">
          <h2>输入统计</h2>
          <dl>
            <dt>总包数</dt>
            <dd>{status?.packetsReceived ?? 0}</dd>
            <dt>丢弃包</dt>
            <dd>{status?.packetsDropped ?? 0}</dd>
            <dt>移动包</dt>
            <dd>{status?.movePackets ?? 0}</dd>
            <dt>滚动包</dt>
            <dd>{status?.scrollPackets ?? 0}</dd>
            <dt>点击包</dt>
            <dd>{status?.clickPackets ?? 0}</dd>
            <dt>累计输入事件</dt>
            <dd>{packetRate}</dd>
          </dl>
        </article>

        <article className="card">
          <h2>手感设置</h2>
          <label>
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
          <label>
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
          <label>
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
        <strong>macOS 权限：</strong>首次运行需要在“系统设置 → 隐私与安全性 → 辅助功能”中允许本应用控制电脑。
        <br />
        <strong>Windows 防火墙：</strong>首次启动后请允许 UDP 45454 / TCP 45455 / UDP 45456 通过防火墙。
      </section>
    </main>
  );
}
