use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
#[cfg(target_os = "windows")]
use std::process::Command;

use local_ip_address::list_afinet_netifas;
use serde::Serialize;
use phonepad_protocol::{TCP_CONTROL_PORT, UDP_DISCOVERY_PORT, UDP_INPUT_PORT};

use crate::device_config::DeviceConfig;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingInfo {
    pub local_ip: String,
    pub recommended_ip: String,
    pub all_ips: Vec<String>,
    pub udp_port: u16,
    pub tcp_port: u16,
    pub discovery_port: u16,
    pub connection_url: String,
    pub device_id: String,
    pub device_name: String,
}

pub fn build_pairing_info(device: &DeviceConfig, last_client: Option<&str>) -> PairingInfo {
    let candidates = collect_ipv4_candidates();
    let recommended = select_recommended_ip(&candidates, last_client);
    let all_ips: Vec<String> = candidates.iter().map(|c| c.ip.to_string()).collect();

    let connection_url = if recommended.is_empty() {
        format!(
            "phonepad://pair?tcp={TCP_CONTROL_PORT}&udp={UDP_INPUT_PORT}&discovery={UDP_DISCOVERY_PORT}&id={}&name={}&secret={}",
            urlencoding::encode(&device.device_id),
            urlencoding::encode(&device.device_name),
            urlencoding::encode(&device.pairing_secret),
        )
    } else {
        format!(
            "phonepad://pair?host={}&tcp={TCP_CONTROL_PORT}&udp={UDP_INPUT_PORT}&discovery={UDP_DISCOVERY_PORT}&id={}&name={}&secret={}",
            urlencoding::encode(&recommended),
            urlencoding::encode(&device.device_id),
            urlencoding::encode(&device.device_name),
            urlencoding::encode(&device.pairing_secret),
        )
    };

    PairingInfo {
        connection_url,
        local_ip: recommended.clone(),
        recommended_ip: recommended,
        all_ips,
        udp_port: UDP_INPUT_PORT,
        tcp_port: TCP_CONTROL_PORT,
        discovery_port: UDP_DISCOVERY_PORT,
        device_id: device.device_id.clone(),
        device_name: device.device_name.clone(),
    }
}

#[derive(Debug, Clone)]
struct IpCandidate {
    ip: Ipv4Addr,
    score: i32,
}

fn collect_ipv4_candidates() -> Vec<IpCandidate> {
    let mut candidates = Vec::new();
    let preferred = preferred_ipv4s();

    if let Ok(interfaces) = list_afinet_netifas() {
        for (_name, ip) in interfaces {
            if let IpAddr::V4(ipv4) = ip {
                if ipv4.is_loopback() {
                    continue;
                }
                if !is_preferred_ipv4(ipv4, preferred.as_deref()) {
                    continue;
                }
                candidates.push(IpCandidate {
                    ip: ipv4,
                    score: score_ipv4(ipv4),
                });
            }
        }
    }

    candidates.sort_by(|a, b| b.score.cmp(&a.score));
    candidates.dedup_by(|a, b| a.ip == b.ip);
    candidates
}

fn select_recommended_ip(candidates: &[IpCandidate], last_client: Option<&str>) -> String {
    let preferred = preferred_ipv4s();
    select_recommended_ip_with_filter(candidates, last_client, preferred.as_deref())
}

fn select_recommended_ip_with_filter(
    candidates: &[IpCandidate],
    last_client: Option<&str>,
    preferred: Option<&[Ipv4Addr]>,
) -> String {
    if let Some(client) = last_client {
        if let Some(ip) = client.split(':').next() {
            if let Ok(peer) = ip.parse::<Ipv4Addr>() {
                if let Some(local) = local_ip_for_peer(peer) {
                    if !is_likely_virtual(local) && is_preferred_ipv4(local, preferred) {
                        return local.to_string();
                    }
                }
            }
        }
    }

    if let Some(ip) = recommended_ip_from_interfaces(preferred) {
        return ip.to_string();
    }

    if let Some(ip) = outbound_ipv4() {
        if candidates.iter().any(|c| c.ip == ip) && is_preferred_ipv4(ip, preferred) {
            return ip.to_string();
        }
    }

    candidates
        .iter()
        .filter(|c| {
            !is_likely_virtual(c.ip) && !c.ip.is_link_local() && is_preferred_ipv4(c.ip, preferred)
        })
        .max_by_key(|c| c.score)
        .map(|c| c.ip.to_string())
        .unwrap_or_default()
}

pub fn discovery_response_ip(peer: SocketAddr) -> String {
    let peer_hint = peer.to_string();
    if let IpAddr::V4(peer_v4) = peer.ip() {
        if let Some(local) = local_ip_for_peer(peer_v4) {
            if !is_likely_virtual(local) {
                return local.to_string();
            }
        }

        let candidates = collect_ipv4_candidates();
        if let Some(local) = candidates
            .iter()
            .find(|c| same_subnet(c.ip, peer_v4) && !is_likely_virtual(c.ip))
        {
            return local.ip.to_string();
        }

        return select_recommended_ip(&candidates, Some(&peer_hint));
    }

    let candidates = collect_ipv4_candidates();
    select_recommended_ip(&candidates, Some(&peer_hint))
}

fn same_subnet(local: Ipv4Addr, peer: Ipv4Addr) -> bool {
    let lo = local.octets();
    let pe = peer.octets();
    if is_rfc1918(local) && is_rfc1918(peer) {
        return lo[0] == pe[0] && lo[1] == pe[1] && lo[2] == pe[2];
    }
    lo[0] == pe[0] && lo[1] == pe[1]
}

fn recommended_ip_from_interfaces(preferred: Option<&[Ipv4Addr]>) -> Option<Ipv4Addr> {
    let mut best: Option<(i32, Ipv4Addr)> = None;

    for iface in default_net::get_interfaces() {
        if !iface.is_up() || iface.is_loopback() || iface.is_tun() {
            continue;
        }
        let label = iface.friendly_name.as_deref().unwrap_or(&iface.name);
        if is_skipped_interface_name(label) {
            continue;
        }

        if let Some(gateway) = &iface.gateway {
            if let IpAddr::V4(gateway_ip) = gateway.ip_addr {
                if let Some(local) = local_ip_for_peer(gateway_ip) {
                    if !is_likely_virtual(local) && is_preferred_ipv4(local, preferred) {
                        push_best(
                            &mut best,
                            score_ipv4(local) + wireless_bonus(label),
                            local,
                        );
                    }
                }
            }
        }

        for v4 in &iface.ipv4 {
            let ip = v4.addr;
            if is_likely_virtual(ip) || ip.is_link_local() {
                continue;
            }
            if !is_preferred_ipv4(ip, preferred) {
                continue;
            }
            push_best(&mut best, score_ipv4(ip) + wireless_bonus(label), ip);
        }
    }

    best.map(|(_, ip)| ip)
}

fn is_preferred_ipv4(ip: Ipv4Addr, preferred: Option<&[Ipv4Addr]>) -> bool {
    preferred.map(|items| items.contains(&ip)).unwrap_or(true)
}

#[cfg(target_os = "windows")]
fn preferred_ipv4s() -> Option<Vec<Ipv4Addr>> {
    use std::os::windows::process::CommandExt;

    const CREATE_NO_WINDOW: u32 = 0x08000000;
    let output = Command::new("powershell")
        .creation_flags(CREATE_NO_WINDOW)
        .args([
            "-NoProfile",
            "-Command",
            "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.AddressState -eq 'Preferred' -and $_.IPAddress -ne '127.0.0.1' -and -not $_.IPAddress.StartsWith('169.254.') } | Select-Object -ExpandProperty IPAddress",
        ])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let ips: Vec<Ipv4Addr> = text.lines().filter_map(|line| line.trim().parse().ok()).collect();
    if ips.is_empty() {
        None
    } else {
        Some(ips)
    }
}

#[cfg(not(target_os = "windows"))]
fn preferred_ipv4s() -> Option<Vec<Ipv4Addr>> {
    None
}

fn push_best(best: &mut Option<(i32, Ipv4Addr)>, score: i32, ip: Ipv4Addr) {
    match best {
        Some((best_score, _)) if score <= *best_score => {}
        _ => *best = Some((score, ip)),
    }
}

fn is_skipped_interface_name(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    [
        "mihomo", "meta", "tap", "tun", "wireguard", "vpn", "virtualbox", "vmware", "hyper-v",
        "wsl", "loopback", "npcap", "bluetooth",
    ]
    .iter()
    .any(|needle| n.contains(needle))
}

fn wireless_bonus(name: &str) -> i32 {
    let n = name.to_ascii_lowercase();
    if n.contains("wi-fi") || n.contains("wlan") || n.contains("wireless") || n.contains("无线") {
        30
    } else {
        0
    }
}

fn outbound_ipv4() -> Option<Ipv4Addr> {
    for target in ["8.8.8.8", "1.1.1.1"] {
        if let Ok(peer) = target.parse::<Ipv4Addr>() {
            if let Some(local) = local_ip_for_peer(peer) {
                if !is_likely_virtual(local) {
                    return Some(local);
                }
            }
        }
    }
    None
}

fn local_ip_for_peer(peer: Ipv4Addr) -> Option<Ipv4Addr> {
    let peer_addr = SocketAddr::from((peer, TCP_CONTROL_PORT));
    let socket = UdpSocket::bind(("0.0.0.0", 0)).ok()?;
    socket.connect(peer_addr).ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if !ip.is_loopback() => Some(ip),
        _ => None,
    }
}

fn score_ipv4(ip: Ipv4Addr) -> i32 {
    let mut score = 0;

    if is_rfc1918(ip) {
        score += 100;
    }
    if ip.octets()[0] == 10 {
        score += 20;
    }
    if is_likely_virtual(ip) {
        score -= 80;
    }
    if ip.is_link_local() {
        score -= 30;
    }

    score
}

fn is_rfc1918(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    (o[0] == 10)
        || (o[0] == 172 && (16..=31).contains(&o[1]))
        || (o[0] == 192 && o[1] == 168)
}

fn is_likely_virtual(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 198 && o[1] == 10
        || o[0] == 100 && (64..=127).contains(&o[1])
        || o[0] == 169 && o[1] == 254
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device_config::DeviceConfig;

    #[test]
    fn virtual_ip_scores_lower_than_rfc1918() {
        let virtual_ip = score_ipv4("198.10.0.1".parse().unwrap());
        let lan_ip = score_ipv4("192.168.1.12".parse().unwrap());
        assert!(lan_ip > virtual_ip);
    }

    #[test]
    fn ten_network_scores_higher_than_192_168() {
        let ten = score_ipv4("10.40.184.10".parse().unwrap());
        let home = score_ipv4("192.168.1.12".parse().unwrap());
        assert!(ten > home);
    }

    #[test]
    fn uses_local_ip_for_last_client_peer() {
        let candidates = vec![
            IpCandidate {
                ip: "192.168.1.12".parse().unwrap(),
                score: score_ipv4("192.168.1.12".parse().unwrap()),
            },
            IpCandidate {
                ip: "10.40.184.10".parse().unwrap(),
                score: score_ipv4("10.40.184.10".parse().unwrap()),
            },
        ];
        let preferred = ["10.40.184.10".parse().unwrap()];
        let selected = select_recommended_ip_with_filter(
            &candidates,
            Some("10.40.184.236:45455"),
            Some(&preferred),
        );
        assert_eq!(selected, "10.40.184.10");
    }

    #[test]
    fn discovery_response_prefers_same_subnet_fallback() {
        let candidates = vec![
            IpCandidate {
                ip: "192.168.1.12".parse().unwrap(),
                score: score_ipv4("192.168.1.12".parse().unwrap()),
            },
            IpCandidate {
                ip: "10.40.184.10".parse().unwrap(),
                score: score_ipv4("10.40.184.10".parse().unwrap()),
            },
        ];
        let same_subnet = candidates
            .iter()
            .find(|c| same_subnet(c.ip, "10.40.184.236".parse().unwrap()))
            .map(|c| c.ip.to_string());
        assert_eq!(same_subnet.as_deref(), Some("10.40.184.10"));
    }

    #[test]
    fn pairing_url_contains_host_when_recommended_ip_available() {
        let device = DeviceConfig {
            device_id: "dev-1".into(),
            device_name: "My PC".into(),
            pairing_secret: "secret123".into(),
        };
        let info = build_pairing_info(&device, Some("10.40.184.236:45455"));
        assert!(info.connection_url.contains("id=dev-1"));
        assert!(info.connection_url.contains("secret=secret123"));
        assert!(info.connection_url.contains("name=My"));
        assert!(info.connection_url.contains("discovery=45456"));
        assert!(info.connection_url.contains("host="));
        assert!(!info.recommended_ip.is_empty());
    }
}
