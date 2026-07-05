use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};

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
        String::new()
    } else {
        format!(
            "phonepad://pair?host={recommended}&tcp={TCP_CONTROL_PORT}&udp={UDP_INPUT_PORT}&id={}&name={}&secret={}",
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

    if let Ok(interfaces) = list_afinet_netifas() {
        for (_name, ip) in interfaces {
            if let IpAddr::V4(ipv4) = ip {
                if ipv4.is_loopback() {
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
    if let Some(client) = last_client {
        if let Some(ip) = client.split(':').next() {
            if let Ok(peer) = ip.parse::<Ipv4Addr>() {
                if let Some(local) = local_ip_for_peer(peer) {
                    return local.to_string();
                }
            }
        }
    }

    candidates
        .iter()
        .max_by_key(|c| c.score)
        .map(|c| c.ip.to_string())
        .unwrap_or_default()
}

pub fn discovery_response_ip(peer: SocketAddr) -> String {
    if let IpAddr::V4(peer_v4) = peer.ip() {
        if let Some(local) = local_ip_for_peer(peer_v4) {
            return local.to_string();
        }
    }

    let candidates = collect_ipv4_candidates();
    select_recommended_ip(&candidates, None)
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
    let octets = ip.octets();
    let mut score = 0;

    if is_rfc1918(ip) {
        score += 100;
    }
    if octets[0] == 192 && octets[1] == 168 {
        score += 40;
    }
    if octets[0] == 10 {
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
    fn returns_empty_when_no_candidates() {
        let selected = select_recommended_ip(&[], None);
        assert_eq!(selected, "");
    }

    #[test]
    fn prefers_rfc1918_over_virtual() {
        let candidates = vec![
            IpCandidate {
                ip: "198.10.0.1".parse().unwrap(),
                score: score_ipv4("198.10.0.1".parse().unwrap()),
            },
            IpCandidate {
                ip: "192.168.1.12".parse().unwrap(),
                score: score_ipv4("192.168.1.12".parse().unwrap()),
            },
        ];
        let selected = select_recommended_ip(&candidates, None);
        assert_eq!(selected, "192.168.1.12");
    }

    #[test]
    fn pairing_url_contains_device_identity() {
        let device = DeviceConfig {
            device_id: "dev-1".into(),
            device_name: "My PC".into(),
            pairing_secret: "secret123".into(),
        };
        let info = build_pairing_info(&device, None);
        if info.connection_url.is_empty() {
            assert!(info.recommended_ip.is_empty());
        } else {
            assert!(info.connection_url.contains("id=dev-1"));
            assert!(info.connection_url.contains("secret=secret123"));
            assert!(info.connection_url.contains("name=My"));
        }
    }
}
