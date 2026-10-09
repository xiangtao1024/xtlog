use std::net::{IpAddr, UdpSocket};

/**
 * 本机 IP 探测（纯标准库实现，不依赖网卡枚举库）。
 *
 * 思路：UDP 的 connect() 不发任何数据包，只是让内核查路由表、绑定一个源地址。
 * 因此「connect 到某个远端地址后读 local_addr」得到的就是本机去往该地址的出口 IP ——
 * 这正是日志服务器上「这条日志来自哪台机器」想要的那个 IP。
 *
 * 优先级：
 *  1. 去往 **xtlog 服务端** 的出口 IP（语义最准：就是服务端看到的那个地址）；
 *  2. 去往公网 DNS 的出口 IP（服务端不可达时的兜底，通常是主网卡）；
 *  3. 都拿不到就返回 None（此时 srcIp 留空，界面不显示 IP）。
 *
 * 注意：机器上装了 WSL / Docker / VPN 时，纯「枚举网卡取第一个」极易拿到虚拟网卡地址
 * （如 172.27.0.1）。路由探测法天然规避了这个问题，因为虚拟网卡一般不承担出网路由。
 * 若仍有偏差，在 config.json 里写死 srcIp 即可。
 */
pub fn detect_local_ip(server_host: &str, server_port: u16) -> Option<String> {
    // 1) 去往服务端的出口 IP
    if !server_host.is_empty() {
        if let Some(ip) = probe(server_host, server_port) {
            return Some(ip);
        }
    }
    // 2) 兜底：公网 DNS
    for (h, p) in [("223.5.5.5", 80u16), ("119.29.29.29", 53), ("8.8.8.8", 53)] {
        if let Some(ip) = probe(h, p) {
            return Some(ip);
        }
    }
    None
}

/// UDP 路由探测：connect 不发包，只查路由表拿源地址
fn probe(host: &str, port: u16) -> Option<String> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect((host, port)).ok()?;
    let addr = sock.local_addr().ok()?;
    match addr.ip() {
        IpAddr::V4(v4) => {
            // 过滤掉回环与链路本地地址（169.254.x.x 往往是拿不到真实路由的产物）
            let o = v4.octets();
            let loopback = o[0] == 127;
            let link_local = o[0] == 169 && o[1] == 254;
            if loopback || link_local {
                None
            } else {
                Some(v4.to_string())
            }
        }
        IpAddr::V6(_) => None, // src_ip 列按 IPv4 设计，且 IPv6 辨识度低
    }
}
